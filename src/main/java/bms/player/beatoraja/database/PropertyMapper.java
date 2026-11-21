package bms.player.beatoraja.database;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plain-reflection bean/property mapper used by the database layer.
 *
 * <p>The database rows are mapped without {@code java.beans.Introspector}/{@code PropertyDescriptor}
 * (not part of the runtime), so this class implements the small part the game needs:</p>
 * <ul>
 *   <li>column label (case-insensitive) to writable bean property (setter) mapping;</li>
 *   <li>typed column reads for the primitive/wrapper/String/Timestamp types used by the game, with
 *       {@code null} passed to object setters and zero/false defaults for primitive setters;</li>
 *   <li>bean property (getter) lookup by column name for the reverse direction used when inserting
 *       rows.</li>
 * </ul>
 *
 * <p>Unknown columns, read-only properties and properties without a public setter are ignored, the
 * same way DbUtils skips unmapped columns.</p>
 */
public final class PropertyMapper {

	private static final Map<Class<?>, Map<String, Method>> SETTERS = new ConcurrentHashMap<>();
	private static final Map<Class<?>, Map<String, Method>> GETTERS = new ConcurrentHashMap<>();

	private PropertyMapper() {
	}

	/**
	 * Reads a bean property through its public getter, matching the property name case-insensitively.
	 * Used by the table insert helper in place of {@code java.beans.PropertyDescriptor}.
	 */
	public static Object readProperty(Object bean, String propertyName) throws ReflectiveOperationException {
		if (bean == null || propertyName == null) {
			throw new IllegalArgumentException("bean == null || propertyName == null");
		}
		Method getter = getters(bean.getClass()).get(propertyName.toLowerCase(Locale.ROOT));
		if (getter == null) {
			throw new NoSuchMethodException("No getter for property '" + propertyName + "' on "
					+ bean.getClass().getName());
		}
		return getter.invoke(bean);
	}

	/**
	 * Maps the current row of {@code rs} onto a new instance of {@code type}. The caller is
	 * responsible for calling {@link ResultSet#next()}.
	 */
	static <T> T mapRow(ResultSet rs, Class<T> type) throws SQLException {
		final T bean;
		try {
			bean = type.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new SQLException("Cannot create bean " + type.getName(), e);
		}

		final ResultSetMetaData metaData = rs.getMetaData();
		final int columnCount = metaData.getColumnCount();
		final Map<String, Method> setters = setters(type);
		for (int i = 1; i <= columnCount; i++) {
			String label = metaData.getColumnLabel(i);
			if (label == null || label.isEmpty()) {
				label = metaData.getColumnName(i);
			}
			if (label == null || label.isEmpty()) {
				continue;
			}
			final Method setter = setters.get(label.toLowerCase(Locale.ROOT));
			if (setter == null) {
				continue;
			}
			final Class<?> targetType = setter.getParameterTypes()[0];
			Object value = readColumn(rs, i, targetType);
			if (value != null && !targetType.isPrimitive() && !targetType.isInstance(value)) {
				value = convertValue(value, targetType);
			}
			try {
				setter.invoke(bean, value);
			} catch (IllegalAccessException e) {
				throw new SQLException("Cannot set property '" + label + "' on " + type.getName(), e);
			} catch (InvocationTargetException e) {
				throw new SQLException("Cannot set property '" + label + "' on " + type.getName(), e.getCause());
			} catch (IllegalArgumentException e) {
				throw new SQLException("Cannot set property '" + label + "' on " + type.getName()
						+ ": incompatible type " + (value == null ? "null" : value.getClass().getName()), e);
			}
		}
		return bean;
	}

	private static Object readColumn(ResultSet rs, int index, Class<?> targetType) throws SQLException {
		// Mirrors DbUtils.processColumn(): NULL is passed as null to object properties (leaving the
		// bean default untouched only for primitives, where the typed getters return 0/false).
		if (!targetType.isPrimitive() && rs.getObject(index) == null) {
			return null;
		}
		if (targetType == String.class) {
			return rs.getString(index);
		}
		if (targetType == int.class || targetType == Integer.class) {
			return rs.getInt(index);
		}
		if (targetType == boolean.class || targetType == Boolean.class) {
			return rs.getBoolean(index);
		}
		if (targetType == long.class || targetType == Long.class) {
			return rs.getLong(index);
		}
		if (targetType == double.class || targetType == Double.class) {
			return rs.getDouble(index);
		}
		if (targetType == float.class || targetType == Float.class) {
			return rs.getFloat(index);
		}
		if (targetType == short.class || targetType == Short.class) {
			return rs.getShort(index);
		}
		if (targetType == byte.class || targetType == Byte.class) {
			return rs.getByte(index);
		}
		if (targetType == Timestamp.class) {
			return rs.getTimestamp(index);
		}
		return rs.getObject(index);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object convertValue(Object value, Class<?> targetType) throws SQLException {
		if (targetType.isEnum()) {
			if (value instanceof String) {
				try {
					return Enum.valueOf((Class<? extends Enum>) targetType, (String) value);
				} catch (IllegalArgumentException e) {
					throw new SQLException("Unknown enum value '" + value + "' for " + targetType.getName(), e);
				}
			}
			throw new SQLException("Cannot convert " + value.getClass().getName() + " to " + targetType.getName());
		}
		if (value instanceof Number) {
			Number number = (Number) value;
			if (targetType == Integer.class) {
				return number.intValue();
			}
			if (targetType == Long.class) {
				return number.longValue();
			}
			if (targetType == Short.class) {
				return number.shortValue();
			}
			if (targetType == Byte.class) {
				return number.byteValue();
			}
			if (targetType == Float.class) {
				return number.floatValue();
			}
			if (targetType == Double.class) {
				return number.doubleValue();
			}
		}
		if (targetType == Boolean.class && value instanceof Number) {
			return ((Number) value).intValue() != 0;
		}
		throw new SQLException("Cannot convert " + value.getClass().getName() + " to " + targetType.getName());
	}

	private static Map<String, Method> setters(Class<?> type) {
		Map<String, Method> cached = SETTERS.get(type);
		if (cached != null) {
			return cached;
		}
		Map<String, Method> map = new HashMap<>();
		for (Method method : type.getMethods()) {
			if (Modifier.isStatic(method.getModifiers())) {
				continue;
			}
			String name = method.getName();
			if (name.length() <= 3 || !name.startsWith("set")) {
				continue;
			}
			if (method.getParameterTypes().length != 1 || method.getReturnType() != void.class) {
				continue;
			}
			String property = decapitalize(name.substring(3));
			if (property.isEmpty()) {
				continue;
			}
			map.putIfAbsent(property.toLowerCase(Locale.ROOT), method);
		}
		SETTERS.put(type, map);
		return map;
	}

	private static Map<String, Method> getters(Class<?> type) {
		Map<String, Method> cached = GETTERS.get(type);
		if (cached != null) {
			return cached;
		}
		Map<String, Method> map = new HashMap<>();
		for (Method method : type.getMethods()) {
			if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
					|| method.getReturnType() == void.class) {
				continue;
			}
			String name = method.getName();
			String property;
			if (name.length() > 3 && name.startsWith("get")) {
				property = decapitalize(name.substring(3));
			} else if (name.length() > 2 && name.startsWith("is")
					&& (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
				property = decapitalize(name.substring(2));
			} else {
				continue;
			}
			if (property.isEmpty()) {
				continue;
			}
			map.putIfAbsent(property.toLowerCase(Locale.ROOT), method);
		}
		GETTERS.put(type, map);
		return map;
	}

	/** The decapitalization rule of {@code java.beans.Introspector}. */
	private static String decapitalize(String name) {
		if (name.isEmpty()) {
			return name;
		}
		if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
			return name;
		}
		return Character.toLowerCase(name.charAt(0)) + name.substring(1);
	}
}
