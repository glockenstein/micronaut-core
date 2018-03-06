/*
 * Copyright 2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.particleframework.function.client.aop;

import io.reactivex.Flowable;
import io.reactivex.Maybe;
import org.particleframework.aop.MethodInterceptor;
import org.particleframework.aop.MethodInvocationContext;
import org.particleframework.core.async.publisher.Publishers;
import org.particleframework.core.convert.ConversionService;
import org.particleframework.core.naming.NameUtils;
import org.particleframework.core.type.Argument;
import org.particleframework.core.type.ReturnType;
import org.particleframework.function.client.FunctionDefinition;
import org.particleframework.function.client.FunctionDiscoveryClient;
import org.particleframework.function.client.FunctionInvoker;
import org.particleframework.function.client.FunctionInvokerChooser;
import org.particleframework.function.client.exceptions.FunctionExecutionException;
import org.particleframework.function.client.exceptions.FunctionNotFoundException;
import org.particleframework.http.annotation.Body;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * Implements advice for the {@link org.particleframework.function.client.FunctionClient} annotation
 *
 * @author graemerocher
 * @since 1.0
 */
@Singleton
public class FunctionClientAdvice  implements MethodInterceptor<Object,Object> {


    private final FunctionDiscoveryClient discoveryClient;
    private final FunctionInvokerChooser functionInvokerChooser;

    public FunctionClientAdvice(FunctionDiscoveryClient discoveryClient, FunctionInvokerChooser functionInvokerChooser) {
        this.discoveryClient = discoveryClient;
        this.functionInvokerChooser = functionInvokerChooser;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        Map<String, Object> parameterValueMap = context.getParameterValueMap();
        int len = parameterValueMap.size();

        Object body;
        if(len == 1) {
            Optional<Argument> bodyArg = Arrays.stream(context.getArguments()).filter(arg -> arg.getAnnotation(Body.class) != null).findFirst();
            if(bodyArg.isPresent()) {
                body = parameterValueMap.get(bodyArg.get().getName());
            }
            else {
                body = parameterValueMap;
            }
        }
        else {
            body = parameterValueMap;
        }

        String functionName = context.getValue(Named.class, String.class).orElse(NameUtils.hyphenate(context.getMethodName(), true));

        Flowable<FunctionDefinition> functionDefinition = Flowable.fromPublisher(discoveryClient.getFunction(functionName));
        ReturnType<Object> returnType = context.getReturnType();
        Class<Object> javaReturnType = returnType.getType();
        if(Publishers.isConvertibleToPublisher(javaReturnType)) {

            Maybe flowable = functionDefinition.firstElement().flatMap(def -> {
                FunctionInvoker functionInvoker = functionInvokerChooser.choose(def).orElseThrow(() -> new FunctionNotFoundException(def.getName()));
                return (Maybe) functionInvoker.invoke(def, body, Argument.of(Maybe.class, returnType.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT)));
            });
            flowable = flowable.switchIfEmpty(Maybe.error(new FunctionNotFoundException(functionName)));
            return ConversionService.SHARED.convert(flowable, returnType.asArgument()).orElseThrow(()-> new FunctionExecutionException("Unsupported reactive type: " + returnType.getType()));
        }
        else {
            // blocking operation
            FunctionDefinition def = functionDefinition.blockingFirst();
            FunctionInvoker functionInvoker = functionInvokerChooser.choose(def).orElseThrow(() -> new FunctionNotFoundException(def.getName()));
            return functionInvoker.invoke(def, body, returnType.asArgument());
        }
    }
}