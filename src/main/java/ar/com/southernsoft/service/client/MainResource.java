package ar.com.southernsoft.service.client;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ClientRepresentation;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Path("/compare-clients")
public class MainResource {

    @Inject
    @io.quarkus.agroal.DataSource("default")
    DataSource dataSource;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response compareClients(Map<String, String> requestBody) {
        String realm = requestBody.get("realm");
        String keycloakUrl = requestBody.get("keycloakUrl");
        String adminUser = requestBody.get("adminUser");
        String adminPass = requestBody.get("adminPass");
        String adminClientId = requestBody.getOrDefault("adminClientId", "admin-cli");
        try {
            // 1. Autenticación con Keycloak
            Keycloak keycloak = KeycloakBuilder.builder()
                    .serverUrl(keycloakUrl)
                    .realm("master")
                    .username(adminUser)
                    .password(adminPass)
                    .clientId(adminClientId)
                    .build();

            // 2. Obtener clientes de Keycloak
            List<ClientRepresentation> kcClients = keycloak.realm(realm).clients().findAll();
            Set<String> kcClientIds = kcClients.stream()
                    .map(ClientRepresentation::getClientId)
                    .collect(Collectors.toSet());

            // 3. Obtener clientes de la base de datos
            Map<String, Map<String, Object>> dbClients = new HashMap<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT client_id, level, client_name, enabled, tipo_autenticacion FROM clients")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> client = new HashMap<>();
                    client.put("client_id", rs.getString("client_id"));
                    client.put("level", rs.getString("level"));
                    client.put("client_name", rs.getString("client_name"));
                    client.put("enabled", rs.getBoolean("enabled"));
                    client.put("tipo_autenticacion", rs.getString("tipo_autenticacion"));
                    dbClients.put(rs.getString("client_id"), client);
                }
            }

            // 4. Apareo y diferencias
            Set<String> dbClientIds = dbClients.keySet();

            Set<String> onlyInKeycloak = new HashSet<>(kcClientIds);
            onlyInKeycloak.removeAll(dbClientIds);

            Set<String> onlyInDb = new HashSet<>(dbClientIds);
            onlyInDb.removeAll(kcClientIds);
            Map<String, Object> result = new HashMap<>();

            List<Map<String, Object>> onlyInDbDetails = onlyInDb.stream()
                    .map(dbClients::get)
                    .collect(Collectors.toList());
                    Set<String> inBoth = new HashSet<>(kcClientIds);
                    inBoth.retainAll(dbClientIds);

            List<Map<String, Object>> enabledMismatch = inBoth.stream()
                    .filter(clientId -> {
                        Map<String, Object> dbClient = dbClients.get(clientId);
                        boolean dbEnabled = (boolean) dbClient.get("enabled");
                        boolean kcEnabled = kcClients.stream()
                                .filter(kcClient -> kcClient.getClientId().equals(clientId))
                                .findFirst()
                                .map(ClientRepresentation::isEnabled)
                                .orElse(false);
                        return dbEnabled != kcEnabled;
                    })
                    .map(clientId -> {
                        Map<String, Object> mismatch = new HashMap<>();
                        mismatch.put("client_id", clientId);
                        mismatch.put("db_enabled", dbClients.get(clientId).get("enabled"));
                        mismatch.put("kc_enabled", kcClients.stream()
                                .filter(kcClient -> kcClient.getClientId().equals(clientId))
                                .findFirst()
                                .map(ClientRepresentation::isEnabled)
                                .orElse(false));
                        return mismatch;
                    })
                    .collect(Collectors.toList());

            result.put("enabled_mismatch", enabledMismatch);
            result.put("only_in_keycloak", onlyInKeycloak);
            result.put("only_in_db", onlyInDbDetails);
            // 5. Crear clientes que están solo en la base de datos
            List<Map<String, Object>> createdClients = new ArrayList<>();
            for (Map<String, Object> dbClient : onlyInDbDetails) {
                ClientRepresentation newClient = new ClientRepresentation();
                newClient.setClientId((String) dbClient.get("client_id"));
                newClient.setName((String) dbClient.get("client_name"));
                newClient.setEnabled(false);
                Map<String, String> attributes = new HashMap<>();
                attributes.put("created_by", "rest_service");
                newClient.setAttributes(attributes);

                keycloak.realm(realm).clients().create(newClient);

                Map<String, Object> createdClientInfo = new HashMap<>(dbClient);
                createdClientInfo.put("created_by", "rest_service");
                createdClientInfo.put("enabled", false);
                createdClients.add(createdClientInfo);
            }

            result.put("created_clients", createdClients);
            return Response.ok(result).build();

        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }
}
