package com.rabbitframework.dbase.builder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;

import com.rabbitframework.commons.propertytoken.PropertyParser;
import com.rabbitframework.commons.utils.StringUtils;
import com.rabbitframework.dbase.annontations.CacheNamespace;
import com.rabbitframework.dbase.annontations.Create;
import com.rabbitframework.dbase.annontations.Delete;
import com.rabbitframework.dbase.annontations.Insert;
import com.rabbitframework.dbase.annontations.Mapper;
import com.rabbitframework.dbase.annontations.SQLProvider;
import com.rabbitframework.dbase.annontations.Select;
import com.rabbitframework.dbase.annontations.Update;
import com.rabbitframework.dbase.cache.Cache;
import com.rabbitframework.dbase.dataaccess.KeyGenerator;
import com.rabbitframework.dbase.dataaccess.dialect.Dialect;
import com.rabbitframework.dbase.exceptions.BindingException;
import com.rabbitframework.dbase.exceptions.BuilderException;
import com.rabbitframework.dbase.mapping.EntityMap;
import com.rabbitframework.dbase.mapping.EntityProperty;
import com.rabbitframework.dbase.mapping.GenerationType;
import com.rabbitframework.dbase.mapping.MappedStatement;
import com.rabbitframework.dbase.mapping.RowBounds;
import com.rabbitframework.dbase.mapping.SqlCommendType;
import com.rabbitframework.dbase.mapping.binding.EntityRegistry;
import com.rabbitframework.dbase.mapping.binding.MapperMethod;
import com.rabbitframework.dbase.mapping.param.WhereParamType;
import com.rabbitframework.dbase.mapping.rowmapper.RowMapperUtil;
import com.rabbitframework.dbase.scripting.LanguageDriver;
import com.rabbitframework.dbase.scripting.SqlSource;

/**
 * Mapper解析类
 */
public class MapperParser {
	private static final Logger logger = LoggerFactory.getLogger(MapperParser.class);
	private final Set<Class<? extends Annotation>> sqlAnnotationType = new HashSet<Class<? extends Annotation>>();
	private Configuration configuration;
	private Class<?> mapperInterface;
	private MapperBuilderAssistant assistant;
	private Properties properties = null;

	public MapperParser(Configuration configuration, Class<?> mapperInterface) {
		this.configuration = configuration;
		this.mapperInterface = mapperInterface;
		assistant = new MapperBuilderAssistant(configuration);
		sqlAnnotationType.add(Insert.class);
		sqlAnnotationType.add(Delete.class);
		sqlAnnotationType.add(Update.class);
		sqlAnnotationType.add(Select.class);
		sqlAnnotationType.add(Create.class);
	}

	/**
	 * mapper接口注解解析
	 */
	public void parse() {
		String mapperInterfaceName = mapperInterface.getName();
		logger.trace("mapper className:" + mapperInterfaceName);
		Mapper mapperAnnotation = mapperInterface.getAnnotation(Mapper.class);
		String catalog = mapperAnnotation.catalog();
		String resource = mapperInterface.toString();
		assistant.setCatalog(catalog);
		try {
			Field[] fields = mapperInterface.getFields();
			if (fields != null && fields.length > 0) {
				properties = new Properties();
				for (int i = 0; i < fields.length; i++) {
					Field field = fields[i];
					String fieldName = field.getName();
					String obj = field.get(field.getType()).toString();
					properties.setProperty(fieldName, obj);
				}
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			throw new RuntimeException(e);
		}

		if (!configuration.isMapperLoaded(resource)) {
			configuration.addLoadedMapper(resource);
			Method[] methods = mapperInterface.getMethods();
			for (Method method : methods) {
				parsetMapperStatement(method);
			}
		}
	}

	/**
	 * mapper方法解析
	 *
	 * @param method
	 */
	@SuppressWarnings("rawtypes")
	private void parsetMapperStatement(Method method) {
		final String mappedStatementId = mapperInterface.getName() + "." + method.getName(); // 声明ID
		Class<?> parameterType = getParameterType(method);
		LanguageDriver languageDriver = configuration.getLanguageDriver();
		SQLParser sqlParser = getSQLParserByAnnotations(method);
		if (sqlParser == null)
			return;

		SqlCommendType sqlCommendType = sqlParser.getSqlCommendType();
		CacheNamespace cacheNamespace = method.getAnnotation(CacheNamespace.class);
		Cache cache = null;
		String[] cacheKey = null;
		if (cacheNamespace != null) {
			String pool = cacheNamespace.pool();
			cache = configuration.getCache(pool);
			cacheKey = cacheNamespace.key();
		}
		boolean isPage = isPage(method, sqlCommendType);
		SqlSource sqlSource = getSqlSource(getSql(sqlParser, isPage, mappedStatementId, sqlCommendType), languageDriver,
				isPage);
		RowMapper rowMapper = null;
		List<KeyGenerator> keyGenerators = new ArrayList<KeyGenerator>();
		switch (sqlCommendType) {
		case SELECT:
			rowMapper = RowMapperUtil.getRowMapper(method);
			break;
		case INSERT:
			if (parameterType == null) {
				break;
			}
			if (!configuration.getEntityRegistry().hasEntityMap(parameterType.getName())) {
				break;
			}
			List<EntityProperty> idEntityMapping = configuration.getEntityRegistry()
					.getEntityMap(parameterType.getName()).getIdProperties();
			if (idEntityMapping != null && idEntityMapping.size() > 0) {
				for (EntityProperty entityProperty : idEntityMapping) {
					KeyGenerator keyGenerator = new KeyGenerator(entityProperty.getGenerationType(),
							entityProperty.getJavaType(), entityProperty.getProperty(), entityProperty.getColumn(),
							entityProperty.getSelectKey());
					keyGenerators.add(keyGenerator);
				}
			}
			break;
		default:
			break;
		}
		assistant.addMappedStatement(mappedStatementId, sqlCommendType, cache, cacheKey, sqlSource, languageDriver,
				keyGenerators, rowMapper);
	}

	private String getSql(SQLParser sqlParser, boolean ispage, String mappedStatementId,
			SqlCommendType sqlCommendType) {
		String sql;
		if (ispage) {
			MappedStatement.Builder statementBuilder = new MappedStatement.Builder(configuration, mappedStatementId,
					sqlCommendType, this.assistant.getCatalog());
			Dialect dialect = configuration.getEnvironment().getDataSourceFactory()
					.getDialect(statementBuilder.build());
			sql = dialect.getSQL(sqlParser.getSqlValue());
		} else {
			sql = sqlParser.getSqlValue();
		}
		if (properties != null) {
			sql = PropertyParser.parseOther("@{", "}", sql, properties);
		}
		return sql;
	}

	private boolean isPage(Method method, SqlCommendType commendType) {
		boolean pageFlag = false;
		if (commendType != SqlCommendType.SELECT) {
			return pageFlag;
		}
		Class<?>[] parameterTypes = method.getParameterTypes();
		for (int i = 0; i < parameterTypes.length; i++) {
			if (RowBounds.class.isAssignableFrom(parameterTypes[i])) {
				pageFlag = true;
				break;
			}
		}
		return pageFlag;
	}

	private SqlSource getSqlSource(String sqlValue, LanguageDriver languageDriver, boolean isPage) {
		final StringBuilder sqlBuilder = new StringBuilder();
		sqlBuilder.append("<script>");
		sqlBuilder.append(sqlValue);
		sqlBuilder.append("</script>");
		return languageDriver.createSqlSource(configuration, sqlBuilder.toString());
	}

	/**
	 * 获取SQL解析值{@link com.rabbitframework.dbase.builder.MapperParser.SQLParser}
	 *
	 * @param method
	 *            mapper方法
	 * @return
	 */
	private SQLParser getSQLParserByAnnotations(Method method) {
		try {
			String sqlValue = "";
			SqlCommendType sqlCommendType = null;
			Class<? extends Annotation> sqlAnnotationType = getAnnotationType(method);
			SQLProvider sqlProviderAnnotation = method.getAnnotation(SQLProvider.class);
			if (sqlAnnotationType != null) {
				if (sqlProviderAnnotation != null) {
					throw new BindingException(
							"You cannot supply both a static SQL and SQLProvider to method named " + method.getName());
				}
				sqlCommendType = SqlCommendType.valueOf(sqlAnnotationType.getSimpleName().toUpperCase(Locale.ENGLISH));
				Annotation sqlAnnotation = method.getAnnotation(sqlAnnotationType);
				sqlValue = (String) sqlAnnotation.getClass().getMethod("value").invoke(sqlAnnotation);
			} else if (sqlProviderAnnotation != null) {
				sqlValue = getSQLValueBySqlProvider(sqlProviderAnnotation);
				if (StringUtils.isBlank(sqlValue)) {
					throw new BuilderException("Error creating SQLParser for SQLProvider.method is null");
				}
				sqlCommendType = sqlProviderAnnotation.sqlType();
			}

			SQLParser sqlParser = null;
			if (sqlCommendType != null) {
				Class<?> paramType = getParameterType(method);
				sqlParser = new SQLParser(sqlValue, sqlCommendType, paramType, configuration);
			}
			return sqlParser;
		} catch (Exception e) {
			throw new BuilderException("Could not find value method on SQL annontation. Cause: " + e, e);
		}
	}

	private Class<? extends Annotation> getAnnotationType(Method method) {
		for (Class<? extends Annotation> type : sqlAnnotationType) {
			Annotation annotation = method.getAnnotation(type);
			if (annotation != null) {
				return type;
			}
		}
		return null;
	}

	private String getSQLValueBySqlProvider(SQLProvider sqlProviderAnnotation) throws Exception {
		String sqlValue = "";
		String providerMethod = sqlProviderAnnotation.method();
		if (StringUtils.isBlank(providerMethod)) {
			return sqlValue;
		}
		Class<?> typeClazz = sqlProviderAnnotation.type();
		for (Method method : typeClazz.getMethods()) {
			if (providerMethod.equals(method.getName())) {
				if (method.getReturnType() == String.class) {
					sqlValue = (String) method.invoke(typeClazz.newInstance());
					break;
				}
			}
		}
		return sqlValue;
	}

	private static class SQLParser {
		private String sqlValue;
		private SqlCommendType sqlCommendType;
		private Class<?> paramType;

		public SQLParser(String sqlValueSrc, SqlCommendType sqlCommendType, Class<?> paramType,
				Configuration configuration) {
			this.sqlCommendType = sqlCommendType;
			this.paramType = paramType;
			if (sqlCommendType == SqlCommendType.INSERT || sqlCommendType == SqlCommendType.UPDATE) {
				getInsertOrUpdateSql(sqlValueSrc, configuration);
			} else if (sqlCommendType == SqlCommendType.SELECT && paramType == WhereParamType.class) {
				String where = getSearchSql();
				Pattern pattern = Pattern.compile("\\$\\$\\{(.*?)\\}");
				Matcher matcher = pattern.matcher(sqlValueSrc);
				ArrayList<String> strs = new ArrayList<String>();
				while (matcher.find()) {
					strs.add(matcher.group(1));
				}
				if (strs.size() > 0) {
					Properties properties = new Properties();
					properties.put(strs.get(0), where);
					this.sqlValue = PropertyParser.parseOther("$${", "}", sqlValueSrc, properties);
				} else {
					this.sqlValue = sqlValueSrc + " " + where + " ";
				}
			} else {
				this.sqlValue = sqlValueSrc;
			}
		}

		public SqlCommendType getSqlCommendType() {
			return sqlCommendType;
		}

		public String getSqlValue() {
			return sqlValue;
		}

		private String getSearchSql() {
			StringBuilder builder = new StringBuilder();
			builder.append("<where>");
			builder.append("<foreach collection=\"oredCriteria\" item=\"criteria\" separator=\"or\" >");
			builder.append("<if test=\"criteria.valid\" >");
			builder.append("<trim prefix=\"(\" suffix=\")\" prefixOverrides=\"and\" >");
			builder.append("<foreach collection=\"criteria.criteria\" item=\"criterion\" >");
			builder.append("<choose>");
			builder.append("<when test=\"criterion.noValue\" >");
			builder.append("and ${criterion.condition}");
			builder.append("</when>");
			builder.append("<when test=\"criterion.singleValue\" >");
			builder.append("and ${criterion.condition} #{criterion.value}");
			builder.append("</when>");
			builder.append("<when test=\"criterion.betweenValue\" >");
			builder.append("and ${criterion.condition} #{criterion.value} and #{criterion.secondValue}");
			builder.append("</when>");
			builder.append("<when test=\"criterion.listValue\" >");
			builder.append("and ${criterion.condition}");
			builder.append(
					"<foreach collection=\"criterion.value\" item=\"listItem\" open=\"(\" close=\")\" separator=\",\" >");
			builder.append("#{listItem}").append("</foreach>").append("</when>").append("</choose>")
					.append("</foreach>").append("</trim>").append("</if>").append("</foreach>").append("</where>");
			return builder.toString();
		}

		private void getInsertOrUpdateSql(String sqlValueSrc, Configuration configuration) {
			if (StringUtils.isNotBlank(sqlValueSrc) || paramType == null) {
				this.sqlValue = sqlValueSrc;
			} else {
				EntityRegistry entityRegistry = configuration.getEntityRegistry();
				String paramTypeName = paramType.getName();
				boolean isEntity = entityRegistry.hasEntityMap(paramTypeName);
				if (isEntity) {
					EntityMap entityMap = entityRegistry.getEntityMap(paramTypeName);
					if (sqlCommendType == SqlCommendType.INSERT) {
						this.sqlValue = getInsertSql(entityMap);
					} else {
						this.sqlValue = getUpdateSql(entityMap);
					}
				} else {
					this.sqlValue = sqlValueSrc;
				}
			}
		}

		private String getUpdateSql(EntityMap entityMap) {
			StringBuilder sbPrefix = new StringBuilder();
			List<EntityProperty> propertyMapping = entityMap.getColumnProperties();
			sbPrefix.append("update ");
			sbPrefix.append(entityMap.getTableName());
			sbPrefix.append(" ");
			sbPrefix.append("<trim prefix=\"set \" suffixOverrides=\",\" >");

			for (EntityProperty entityMapping : propertyMapping) {
				String column = entityMapping.getColumn();
				String property = entityMapping.getProperty();
				sbPrefix.append("<if test=\"" + property + " != null\" >").append(column).append("=").append("#{")
						.append(property).append("}").append(",").append("</if>");
			}
			sbPrefix.append("</trim>");
			sbPrefix.append(" ");
			sbPrefix.append(" where ");
			sbPrefix.append("<trim suffix=\" \" suffixOverrides=\"and\" >");
			List<EntityProperty> idMapping = entityMap.getIdProperties();
			for (EntityProperty entityMapping : idMapping) {
				String column = entityMapping.getColumn();
				String property = entityMapping.getProperty();
				sbPrefix.append(column).append("=").append("#{").append(property).append("}").append(" and ");
			}
			sbPrefix.append("</trim>");
			String updateSqlScript = sbPrefix.toString();
			return updateSqlScript;
		}

		private String getInsertSql(EntityMap entityMap) {
			StringBuilder sbPrefix = new StringBuilder();
			sbPrefix.append(" insert into ");
			sbPrefix.append(entityMap.getTableName());
			sbPrefix.append(" ");
			sbPrefix.append("<trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\" >");
			StringBuilder sbSuffix = new StringBuilder();
			sbSuffix.append("<trim prefix=\"values (\" suffix=\")\" suffixOverrides=\",\" >");
			List<EntityProperty> identityMapping = entityMap.getIdProperties();
			for (EntityProperty entityMapping : identityMapping) {
				String column = entityMapping.getColumn();
				String property = entityMapping.getProperty();
				GenerationType genType = entityMapping.getGenerationType();
				if (GenerationType.IDENTITY.equals(genType)) {
					continue;
				}
				sbPrefix.append(column);
				sbPrefix.append(",");
				sbSuffix.append("#{");
				sbSuffix.append(property);
				sbSuffix.append("}");
				sbSuffix.append(",");
			}

			List<EntityProperty> propertyMapping = entityMap.getColumnProperties();
			for (EntityProperty entityMapping : propertyMapping) {
				String column = entityMapping.getColumn();
				String property = entityMapping.getProperty();
				sbPrefix.append("<if test=\"" + property + " != null\" >");
				sbPrefix.append(column);
				sbPrefix.append(",");
				sbPrefix.append("</if>");

				sbSuffix.append("<if test=\"" + property + " != null\" >");
				sbSuffix.append("#{");
				sbSuffix.append(property);
				sbSuffix.append("}");
				sbSuffix.append(",");
				sbSuffix.append("</if>");
			}

			sbPrefix.append("</trim>");
			sbSuffix.append("</trim>");
			String insertSql = sbPrefix.toString() + sbSuffix.toString();
			return insertSql;
		}
	}

	/**
	 * 获取mapper方法中参数类型
	 * <p/>
	 * 多个参数时使用
	 * {@link com.rabbitframework.dbase.mapping.binding.MapperMethod.ParamMap}
	 *
	 * @param method
	 * @return
	 */
	private Class<?> getParameterType(Method method) {
		Class<?> parameterType = null;
		for (Class<?> mParameterType : method.getParameterTypes()) {
			if (!RowBounds.class.isAssignableFrom(mParameterType)) {
				if (parameterType == null) {
					parameterType = mParameterType;
				} else {
					parameterType = MapperMethod.ParamMap.class;
					break;
				}
			}
		}
		return parameterType;
	}
}
