package io.github.MVennn.WxPicLabel.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

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
