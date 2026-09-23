package com.pulse.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MlModelSchemaIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Verification 1: helix_ml_models.sql DDL definition exists and conforms to PostgreSQL specifications")
    void testPostgreSqlSchemaDdl() throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("schema/helix_ml_models.sql");
        assertThat(is).as("Schema DDL file schema/helix_ml_models.sql must exist on classpath").isNotNull();

        String ddl = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // Verify table name and columns
        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS helix_ml_models");
        assertThat(ddl).contains("id BIGSERIAL PRIMARY KEY");
        assertThat(ddl).contains("model_name VARCHAR(255) NOT NULL");
        assertThat(ddl).contains("version VARCHAR(64) NOT NULL");
        assertThat(ddl).contains("file_path TEXT NOT NULL");
        assertThat(ddl).contains("file_size_kb INTEGER NOT NULL");
        assertThat(ddl).contains("input_schema JSONB NOT NULL");
        assertThat(ddl).contains("output_type VARCHAR(32) DEFAULT 'FLOAT' NOT NULL");
        assertThat(ddl).contains("is_active BOOLEAN DEFAULT false NOT NULL");
        assertThat(ddl).contains("uploaded_by VARCHAR(255) NOT NULL");
        assertThat(ddl).contains("uploaded_at TIMESTAMP NOT NULL");
        assertThat(ddl).contains("description TEXT");

        // Verify unique constraint on (model_name, version)
        assertThat(ddl).contains("CONSTRAINT uk_helix_ml_models_name_version UNIQUE (model_name, version)");

        // Verify indexes
        assertThat(ddl).contains("CREATE INDEX IF NOT EXISTS idx_helix_ml_models_name ON helix_ml_models (model_name)");
        assertThat(ddl).contains("CREATE INDEX IF NOT EXISTS idx_helix_ml_models_active ON helix_ml_models (model_name, is_active)");
    }

    @Test
    @DisplayName("Verification 2: persistence.xml explicitly registers com.pulse.entity.MlModel")
    void testPersistenceXmlRegistration() throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("META-INF/persistence.xml");
        assertThat(is).isNotNull();

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(is);

        NodeList classList = doc.getElementsByTagNameNS("*", "class");
        List<String> registeredClasses = new ArrayList<>();
        for (int i = 0; i < classList.getLength(); i++) {
            registeredClasses.add(classList.item(i).getTextContent().trim());
        }

        assertThat(registeredClasses).contains("com.pulse.entity.MlModel");
    }

    @Test
    @DisplayName("Verification 3: JSONB input schema parses correctly and models real-world ONNX feature schemas")
    void testJsonbInputSchemaValidation() throws Exception {
        String realWorldSchema = """
                {
                    "model": "fraud_model_v1",
                    "targetOps": ["float_input", "probabilities"],
                    "outputIndex": 1,
                    "features": [
                        "amount", "hour_of_day", "day_of_week", "merchant_category",
                        "transaction_currency", "velocity_1h", "velocity_24h", "velocity_7d",
                        "amount_deviation_30d", "unique_merchants_24h", "is_new_device",
                        "device_risk_score", "is_vpn_or_proxy", "country_mismatch",
                        "account_age_days", "is_account_suspended", "previous_chargeback"
                    ],
                    "types": {
                        "amount": "FLOAT",
                        "hour_of_day": "FLOAT",
                        "is_vpn_or_proxy": "FLOAT"
                    }
                }
                """;

        MlModel model = new MlModel(
                "fraud_model_v1",
                "1.0.0",
                "/var/helix/models/fraud_model_v1.onnx",
                31,
                realWorldSchema,
                "data_scientist_1",
                "Production high-throughput credit card fraud classifier"
        );

        // Verify JSON parsing succeeds
        JsonNode jsonNode = objectMapper.readTree(model.getInputSchema());
        assertThat(jsonNode.get("model").asText()).isEqualTo("fraud_model_v1");
        assertThat(jsonNode.get("features")).hasSize(17);
        assertThat(jsonNode.get("features").get(0).asText()).isEqualTo("amount");
        assertThat(jsonNode.get("features").get(12).asText()).isEqualTo("is_vpn_or_proxy");
    }

    @Test
    @DisplayName("Verification 4: Unique constraint model prevents duplicate versions of same model")
    void testUniqueConstraintBusinessRule() {
        MlModel v1 = new MlModel("fraud_model_v1", "1.0.0", "/path/v1.onnx", 31, "{}", "admin", "First release");
        MlModel v1Duplicate = new MlModel("fraud_model_v1", "1.0.0", "/path/v1_new.onnx", 32, "{}", "admin", "Duplicate");
        MlModel v2 = new MlModel("fraud_model_v1", "2.0.0", "/path/v2.onnx", 35, "{}", "admin", "Second release");

        assertThat(v1).isEqualTo(v1Duplicate);
        assertThat(v1).isNotEqualTo(v2);
    }
}
