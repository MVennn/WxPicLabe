package io.github.MVennn.WxPicLabel.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 极简反射工具。
 *
 * <p>现代 Xposed API 不再提供 {@code XposedHelpers}，这里只保留本模块真正用到的能力：
 * 按签名找方法/构造器、按名字找字段、宽松调用与取值。所有查找失败统一返回 null，
 * 由调用方决定是否降级，避免任何一个 Hook 点缺失就把整个模块打崩。
 */
public final class Reflect {

    private Reflect() {
    }

    private static final Object NOT_FOUND = new Object();

    /** findMethod 缓存：命中直接复用已 setAccessible 的 Method；查无用 NOT_FOUND 哨兵占位。 */
    private static final ConcurrentHashMap<MethodKey, Object> METHOD_CACHE =
            new ConcurrentHashMap<>();

    /** findField 缓存：命中直接复用已 setAccessible 的 Field；查无用 NOT_FOUND 哨兵占位。 */
    private static final ConcurrentHashMap<FieldKey, Object> FIELD_CACHE =
            new ConcurrentHashMap<>();

    /** 缓存键 = 类身份 + 方法名 + 参数签名；clone 防止调用方事后改数组串味。 */
    private static final class MethodKey {
        final Class<?> clazz;
        final String name;
        final Class<?>[] paramTypes;

        MethodKey(Class<?> clazz, String name, Class<?>[] paramTypes) {
            this.clazz = clazz;
            this.name = name;
            this.paramTypes = paramTypes == null ? null : paramTypes.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MethodKey)) {
                return false;
            }
            MethodKey other = (MethodKey) o;
            return clazz == other.clazz
                    && name.equals(other.name)
                    && Arrays.equals(paramTypes, other.paramTypes);
        }

        @Override
        public int hashCode() {
            int result = 31 * System.identityHashCode(clazz) + name.hashCode();
            // null 参数数组（任意签名）与空数组（无参方法）必须落在不同桶。
            return 31 * result
                    + (paramTypes == null ? 0 : 1 + Arrays.hashCode(paramTypes));
        }
    }

    /** 缓存键 = 类身份 + 候选字段名序列；顺序敏感，与 Arrays.asList 语义一致。 */
    private static final class FieldKey {
        final Class<?> clazz;
        final String[] names;

        FieldKey(Class<?> clazz, String[] names) {
            this.clazz = clazz;
            this.names = names == null ? null : names.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof FieldKey)) {
                return false;
            }
            FieldKey other = (FieldKey) o;
            return clazz == other.clazz && Arrays.equals(names, other.names);
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(clazz);
            // null 名字数组与空数组必须落在不同桶。
            return 31 * result + (names == null ? 0 : 1 + Arrays.hashCode(names));
        }
    }

    /** 改前 findMethod 的扫描逻辑，逐字保留，仅缓存未命中时执行一次。 */
    private static Method lookupMethod(Class<?> clazz, String name, Class<?>[] paramTypes) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                if (paramTypes == null || sameTypes(m.getParameterTypes(), paramTypes)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 改前 findField 的扫描逻辑，逐字保留，仅缓存未命中时执行一次。 */
    private static Field lookupField(Class<?> clazz, String[] names) {
        List<String> wanted = Arrays.asList(names);
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (wanted.contains(f.getName())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    // ------------------------------------------------------------------ 类

    public static Class<?> findClass(String name, ClassLoader cl) {
        if (name == null) {
            return null;
        }
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 在类及其父类链上逐层查找，找到第一个能加载的类。 */
    public static Class<?> findClassAny(String[] names, ClassLoader cl) {
        for (String n : names) {
            Class<?> c = findClass(n, cl);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    // -------------------------------------------------------------- 方法查找

    /**
     * 按名字 + 参数类型精确匹配方法（含父类链）。
     *
     * @param paramTypes 精确的参数类型；传 null 表示任意参数列表
     */
    public static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        if (clazz == null || name == null) {
            return null;
        }
        MethodKey key = new MethodKey(clazz, name, paramTypes);
        Object hit = METHOD_CACHE.computeIfAbsent(key, k -> {
            Method found = lookupMethod(clazz, name, paramTypes);
            return found == null ? NOT_FOUND : found;
        });
        return hit == NOT_FOUND ? null : (Method) hit;
    }

    /**
     * 按名字 + 参数个数查找方法，多个重载时优先返回第一个。
     *
     * <p>用于微信这种方法名稳定、参数列表随版本微调的场景：拿不到精确签名时退一步，
     * 再在 Hook 内部按 {@code chain.getArgs().size()} 做二次判断。
     */
    public static List<Method> findMethodsByNameAndCount(Class<?> clazz, String name, int argCount) {
        List<Method> out = new ArrayList<>();
        if (clazz == null || name == null) {
            return out;
        }
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argCount) {
                    m.setAccessible(true);
                    out.add(m);
                }
            }
            c = c.getSuperclass();
        }
        return out;
    }

    /** 找所有同名方法，参数个数不限（用于最后兜底探点）。 */
    public static List<Method> findMethodsByName(Class<?> clazz, String... names) {
        List<String> wanted = Arrays.asList(names);
        List<Method> out = new ArrayList<>();
        if (clazz == null) {
            return out;
        }
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (wanted.contains(m.getName())) {
                    m.setAccessible(true);
                    out.add(m);
                }
            }
            c = c.getSuperclass();
        }
        return out;
    }

    public static Constructor<?> findConstructor(Class<?> clazz, Class<?>... paramTypes) {
        if (clazz == null) {
            return null;
        }
        for (Constructor<?> ct : clazz.getDeclaredConstructors()) {
            if (sameTypes(ct.getParameterTypes(), paramTypes)) {
                ct.setAccessible(true);
                return ct;
            }
        }
        return null;
    }

    private static boolean sameTypes(Class<?>[] actual, Class<?>[] expected) {
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            Class<?> a = actual[i];
            Class<?> e = expected[i];
            if (e == null) {
                continue; // null 当通配符用
            }
            if (!wrap(a).isAssignableFrom(wrap(e)) && !wrap(e).isAssignableFrom(wrap(a))) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == char.class) return Character.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        return c;
    }

    // -------------------------------------------------------------- 字段查找

    public static Field findField(Class<?> clazz, String... names) {
        if (clazz == null) {
            return null;
        }
        FieldKey key = new FieldKey(clazz, names);
        Object hit = FIELD_CACHE.computeIfAbsent(key, k -> {
            Field found = lookupField(clazz, names);
            return found == null ? NOT_FOUND : found;
        });
        return hit == NOT_FOUND ? null : (Field) hit;
    }

    /** 收集类层级上所有字段名，用于日志里做「到底有哪几个字段」的排查。 */
    public static List<String> listFieldNames(Class<?> clazz) {
        List<String> out = new ArrayList<>();
        Deque<Class<?>> stack = new ArrayDeque<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            stack.push(c);
            c = c.getSuperclass();
        }
        while (!stack.isEmpty()) {
            for (Field f : stack.pop().getDeclaredFields()) {
                out.add(f.getName());
            }
        }
        return out;
    }

    // -------------------------------------------------------------------- 调用

    public static Object invoke(Object target, Method m, Object... args) {
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object getField(Object target, Field f) {
        if (target == null || f == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object getFieldByNames(Object target, String... names) {
        if (target == null) {
            return null;
        }
        Field f = findField(target.getClass(), names);
        return f == null ? null : getField(target, f);
    }

    public static String getStringField(Object target, String... names) {
        Object v = getFieldByNames(target, names);
        return v instanceof String ? (String) v : (v == null ? null : String.valueOf(v));
    }

    public static long getLongField(Object target, String... names) {
        Object v = getFieldByNames(target, names);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return 0L;
    }

    public static int getIntField(Object target, String... names) {
        Object v = getFieldByNames(target, names);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return 0;
    }
}
