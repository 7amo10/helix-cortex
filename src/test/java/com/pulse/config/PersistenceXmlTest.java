package com.pulse.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceXmlTest {

    @Test
    @DisplayName("persistence.xml should define CortexPU with JTA, CortexDS datasource, and HikariCP pool properties")
    void testPersistenceXmlConfiguration() throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("META-INF/persistence.xml");
        assertThat(is).isNotNull();

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(is);

        NodeList puList = doc.getElementsByTagNameNS("*", "persistence-unit");
        assertThat(puList.getLength()).isGreaterThanOrEqualTo(1);

        Element pu = (Element) puList.item(0);
        assertThat(pu.getAttribute("name")).isEqualTo("CortexPU");
        assertThat(pu.getAttribute("transaction-type")).isEqualTo("JTA");

        Element jtaDs = (Element) doc.getElementsByTagNameNS("*", "jta-data-source").item(0);
        assertThat(jtaDs.getTextContent().trim()).isEqualTo("java:jboss/datasources/CortexDS");

        Element sharedCacheMode = (Element) doc.getElementsByTagNameNS("*", "shared-cache-mode").item(0);
        assertThat(sharedCacheMode.getTextContent().trim()).isEqualTo("ENABLE_SELECTIVE");

        NodeList propList = doc.getElementsByTagNameNS("*", "property");
        Map<String, String> properties = new HashMap<>();
        for (int i = 0; i < propList.getLength(); i++) {
            Element prop = (Element) propList.item(i);
            properties.put(prop.getAttribute("name"), prop.getAttribute("value"));
        }

        assertThat(properties.get("hibernate.dialect")).isEqualTo("org.hibernate.dialect.PostgreSQLDialect");
        assertThat(properties.get("hibernate.hbm2ddl.auto")).isEqualTo("create-drop");
        assertThat(properties.get("hibernate.generate_statistics")).isEqualTo("true");
        assertThat(properties.get("hibernate.statistics.statistics_enabled")).isEqualTo("true");
        assertThat(properties.get("hibernate.hikari.maximumPoolSize")).isEqualTo("16");
        assertThat(properties.get("hibernate.hikari.minimumIdle")).isEqualTo("4");
        assertThat(properties.get("hibernate.hikari.connectionTimeout")).isEqualTo("3000");
        assertThat(properties.get("hibernate.hikari.idleTimeout")).isEqualTo("600000");
        assertThat(properties.get("hibernate.hikari.maxLifetime")).isEqualTo("1800000");
        assertThat(properties.get("hibernate.hikari.poolName")).isEqualTo("CortexPool");
    }
}
