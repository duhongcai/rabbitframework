package com.rabbitframework.generator;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.rabbitframework.commons.utils.StringUtils;
import com.rabbitframework.generator.dataaccess.DatabaseIntrospector;
import com.rabbitframework.generator.mapping.EntityMapping;
import com.rabbitframework.generator.template.JavaModeGenerate;
import com.rabbitframework.generator.template.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitframework.generator.builder.Configuration;
import com.rabbitframework.generator.exceptions.GeneratorException;

public class RabbitGenerator {
    private static final Logger logger = LoggerFactory.getLogger(RabbitGenerator.class);
    private Configuration configuration;

    public RabbitGenerator(Configuration configuration) {
        this.configuration = configuration;
    }

    public void generator() {
        Connection connection = null;
        try {
            connection = configuration.getEnvironment().getDataSource().getConnection();
            DatabaseMetaData metaData = connection.getMetaData();
            DatabaseIntrospector databaseIntrospector = new DatabaseIntrospector(metaData, configuration);
            List<EntityMapping> entityMappings = databaseIntrospector.introspectTables();
            Template template = configuration.getTemplate();
            Map<String, JavaModeGenerate> templateMappingMap = template.getTemplateMapping();
            Map<String, Object> outMap = new HashMap<String, Object>();
            for (Map.Entry<String, JavaModeGenerate> entry : templateMappingMap.entrySet()) {
                String key = entry.getKey();
                JavaModeGenerate javaModeGenerate = entry.getValue();
                String packageName = javaModeGenerate.getTargetPackage();
                String outPath = javaModeGenerate.getTargetProject();
                outMap.put("packageName", packageName);
                for (EntityMapping entityMapping : entityMappings) {
                    outMap.put("entity", entityMapping);
                    template.printToConsole(outMap, key);
                }
            }

        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new GeneratorException(e);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception e) {
                }
            }
        }
    }

}
