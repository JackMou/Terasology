/*
 * Copyright 2014 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.logic.console.internal;

import com.google.common.collect.ImmutableMap;
import org.terasology.logic.console.internal.adapter.CommandParameterAdapterManager;
import org.terasology.logic.console.internal.exceptions.CommandParameterParseException;
import org.terasology.registry.CoreRegistry;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author Limeth
 */
public final class CommandParameter {
    private static final Map<Class<?>, Class<?>> PRIMITIVES_TO_WRAPPERS = new ImmutableMap.Builder<Class<?>, Class<?>>()
            .put(boolean.class, Boolean.class)
            .put(byte.class, Byte.class)
            .put(char.class, Character.class)
            .put(double.class, Double.class)
            .put(float.class, Float.class)
            .put(int.class, Integer.class)
            .put(long.class, Long.class)
            .put(short.class, Short.class)
            .put(void.class, Void.class)
            .build();
    private final String name;
    private final Class<?> type;
    private final Character arrayDelimiter;
    private final boolean required;

    private CommandParameter(String name, Class<?> type, Character arrayDelimiter, boolean required) {
        Objects.requireNonNull(name, "The parameter name must not be null!");

        if (name.length() <= 0) {
            throw new IllegalArgumentException("The parameter name must not be empty!");
        }

        Objects.requireNonNull(type, "The parameter type must not be null!");

        if (type.isPrimitive()) {
            throw new IllegalArgumentException("The parameter type must not be primitive!"
                    + " Use " + PRIMITIVES_TO_WRAPPERS.get(type).getSimpleName()
                    + " instead of " + type.getSimpleName() + ".");
        }

        if(arrayDelimiter != null) {
            if (arrayDelimiter == Command.ARRAY_DELIMITER_ESCAPE_CHARACTER) {
                throw new IllegalArgumentException("The array delimiter must not be the same as the escape character ("
                        + Command.ARRAY_DELIMITER_ESCAPE_CHARACTER + ")!");
            } else if (arrayDelimiter == Command.ARRAY_DELIMITER_VARARGS && required) {
                throw new IllegalArgumentException("A varargs parameter must not be required!");
            }
        }

        this.name = name;
        this.type = type;
        this.arrayDelimiter = arrayDelimiter;
        this.required = required;
    }

    public static CommandParameter single(String name, Class<?> type, boolean required) {
        if (type.isArray()) {
            throw new IllegalArgumentException("The type of a simple CommandParameterDefinition must not be an array!");
        }

        return new CommandParameter(name, type, null, required);
    }

    public static CommandParameter array(String name, Class<?> childType, char arrayDelimiter, boolean required) {
        if (childType.isArray()) {
            throw new IllegalArgumentException("The child type of an array CommandParameterDefinition must not be an array!");
        }

        Class<?> type = getArrayClass(childType);

        return new CommandParameter(name, type, arrayDelimiter, required);
    }

    public static CommandParameter array(String name, Class<?> childType, boolean required) {
        return array(name, childType, Command.ARRAY_DELIMITER_DEFAULT, required);
    }

    public static CommandParameter varargs(String name, Class<?> childType) {
        return array(name, childType, Command.ARRAY_DELIMITER_VARARGS, false);
    }

    /**
     * @param clazz The child class of the array class returned
     * @return The array class of {@code clazz}
     */
    @SuppressWarnings("unchecked")
    private static <T> Class<? extends T[]> getArrayClass(Class<T> clazz) {
        return (Class<? extends T[]>) Array.newInstance(clazz, 0).getClass();
    }

    public Object getValue(String param) throws CommandParameterParseException {
        if (!isArray()) {
            return parseSingle(unescape(param, false).get(0));
        } else {
            List<String> params = unescape(param, true);
            Object array = Array.newInstance(getType(), params.size());

            for (int i = 0; i < Array.getLength(array); i++) {
                Array.set(array, i, parseSingle(params.get(i)));
            }

            return array;
        }
    }

    private List<String> unescape(String rawParameter, boolean split) throws CommandParameterParseException {
        String string = rawParameter;
        List<String> params = new ArrayList<>();
        int i = 0;

        while (i < string.length()) {
            char c = string.charAt(i);

            if (c == Command.ARRAY_DELIMITER_ESCAPE_CHARACTER) {
                if (i >= string.length() - 1) {
                    throw new CommandParameterParseException("The command parameter must not end with an escape character.", rawParameter);
                }

                string = string.substring(0, i) + string.substring(i + 1);
                char following = string.charAt(i);

                if (following != Command.ARRAY_DELIMITER_ESCAPE_CHARACTER && (!split || following != arrayDelimiter)) {
                    throw new CommandParameterParseException("Character '" + following + "' cannot be escaped.", rawParameter);
                }

                i++;
                continue;
            }

            if (split && c == arrayDelimiter) {
                String param = string.substring(0, i);
                string = string.substring(i + 1);
                i = 0;

                params.add(param);
                continue;
            }

            i++;
        }

        if (string.length() > 0) {
            params.add(string);
        }

        return params;
    }

    public Object parseSingle(String string) throws CommandParameterParseException {
        CommandParameterAdapterManager parameterAdapterManager = CoreRegistry.get(CommandParameterAdapterManager.class);
        Class<?> childType = getType();

        if (parameterAdapterManager.isAdapterRegistered(childType)) {
            try {
                return parameterAdapterManager.parse(childType, string);
            } catch (Error | Exception e) {
                throw new CommandParameterParseException("An error occurred while parsing " + getType().getCanonicalName(), string);
            }
        }

        throw new CommandParameterParseException("Cannot parse a " + childType.getCanonicalName(), string);
    }

    public String composeSingle(Object object) {
        CommandParameterAdapterManager parameterAdapterManager = CoreRegistry.get(CommandParameterAdapterManager.class);

        return parameterAdapterManager.compose(object);
    }

    public boolean isEscaped(String string, int charIndex, boolean trail) {
        return charIndex - 1 >= 0 && string.charAt(charIndex - 1) == Command.ARRAY_DELIMITER_ESCAPE_CHARACTER
                && (!trail || !isEscaped(string, charIndex - 1, true));
    }

    public String getUsage() {
        String typeString = getType().getSimpleName();

        if (isArray()) {
            typeString += getArrayDelimiter() + typeString;
        }

        String cmd = typeString + (hasName() ? " " + getName() : "");

        if (required) {
            cmd = "<" + cmd + ">";
        } else {
            cmd = "(" + cmd + ")";
        }

        return cmd;
    }

    public boolean isArray() {
        return type.isArray();
    }

    public boolean isVarargs() {
        return isArray() && getArrayDelimiter() == Command.ARRAY_DELIMITER_VARARGS;
    }

    public Character getArrayDelimiter() {
        return arrayDelimiter;
    }

    public boolean hasName() {
        return name != null;
    }

    public String getName() {
        return name;
    }

    public Class<?> getType() {
        if (type.isArray()) {
            return type.getComponentType();
        }

        return type;
    }

    public Class<?> getTypeRaw() {
        return type;
    }

    public boolean isRequired() {
        return required;
    }
}
