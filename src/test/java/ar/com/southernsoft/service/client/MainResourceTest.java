package ar.com.southernsoft.service.client;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.util.Map;

@QuarkusTest
class MainResourceTest {
    @Test
    void testHelloEndpoint() {
        given()
          .when().get("/hello")
          .then()
             .statusCode(200)
             .body(is("Hello from Quarkus REST"));
    }
    @Test
    void testCompareClientsSuccess() {
        Map<String, String> requestBody = Map.of(
            "realm", "test-realm",
            "keycloakUrl", "http://localhost:8080",
            "adminUser", "admin",
            "adminPass", "admin",
            "adminClientId", "admin-cli"
        );

        given()
            .contentType("application/json")
            .body(requestBody)
            .when()
            .post("/compare-clients")
            .then()
            .statusCode(200)
            .body("enabled_mismatch", is(notNullValue()))
            .body("only_in_keycloak", is(notNullValue()))
            .body("only_in_db", is(notNullValue()))
            .body("created_clients", is(notNullValue()));
    }

    @Test
    void testCompareClientsMissingField() {
        Map<String, String> requestBody = Map.of(
            "realm", "test-realm",
            "keycloakUrl", "http://localhost:8080",
            "adminUser", "admin"
        );

        given()
            .contentType("application/json")
            .body(requestBody)
            .when()
            .post("/compare-clients")
            .then()
            .statusCode(500)
            .body("error", is(notNullValue()));
    }

    @Test
    void testCompareClientsInvalidKeycloakUrl() {
        Map<String, String> requestBody = Map.of(
            "realm", "test-realm",
            "keycloakUrl", "http://invalid-url",
            "adminUser", "admin",
            "adminPass", "admin",
            "adminClientId", "admin-cli"
        );

        given()
            .contentType("application/json")
            .body(requestBody)
            .when()
            .post("/compare-clients")
            .then()
            .statusCode(500)
            .body("error", is(notNullValue()));
    }
}