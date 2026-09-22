# Conectividad AWS — Pedidos360

Documentación de referencia sobre cómo está desplegada y conectada la
infraestructura del proyecto: SPA → Azure Entra ID (IDaaS) → AWS API Gateway
(API Manager) → BFF (EC2) → Microservicio (EC2).

## 1. Arquitectura

```
SPA (React)
   │  login/token (MSAL)
   ▼
Azure Entra ID  ◄──────────────┐
   │                            │ valida JWT
   │ token                      │ (independiente)
   ▼                            │
AWS API Gateway (API Manager) ──┘
   │ reenvía si el token es válido
   ▼
BFF (EC2 #1, IP pública/elástica, puerto 8088)
   │  valida el JWT otra vez (Spring Security)
   │  reenvía con IP privada
   ▼
Microservicio de Pedidos (EC2 #2, solo IP privada, puerto 8087)
   no valida JWT, confía en el BFF
```

Puntos clave del diseño:
- **API Gateway y BFF validan el JWT cada uno por su cuenta**, de forma
  independiente, contra el mismo tenant de Entra ID (mismo issuer y
  audience). Ninguno reenvía "confianza" al otro.
- El **microservicio nunca queda expuesto a internet**: solo acepta tráfico
  desde la instancia del BFF.

## 2. Red: VPC y Security Groups

Ambas instancias EC2 viven en la **misma VPC** (la VPC por defecto de la
cuenta alcanza y evita tener que configurar VPC Peering). Lo que cambia
entre instancias es el **Security Group**:

| Instancia | Puerto | Origen permitido |
|---|---|---|
| BFF | 22 (SSH) | tu IP |
| BFF | 8088 | `0.0.0.0/0` (público, lo necesita el API Gateway) |
| Microservicio | 22 (SSH) | tu IP |
| Microservicio | 8087 | Security Group del BFF (o su IP privada `/32`) — **nunca público** |

Referencia oficial: [Control traffic to your AWS resources using security groups](https://docs.aws.amazon.com/vpc/latest/userguide/VPC_SecurityGroups.html)

## 3. Elastic IP

Se asigna una **Elastic IP solo a la instancia del BFF** (el microservicio
nunca se conecta desde afuera, no la necesita). Esto evita que la IP pública
cambie cada vez que se detiene/enciende la instancia para ahorrar costos del
sandbox.

Referencia oficial: [Elastic IP addresses — Amazon EC2 User Guide](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/elastic-ip-addresses-eip.html)

Conectarse a las instancias por SSH: [Connect to your Linux instance](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/connect.html)

## 4. Despliegue con Docker

### 4.1 Instalar Docker en cada instancia (Amazon Linux 2023)

```bash
sudo dnf update -y
sudo dnf install -y docker
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker ec2-user
# cerrar sesión SSH y volver a entrar para que tome el grupo docker
```

Referencia oficial: [Install Docker on an Amazon EC2 instance (Amazon Linux 2023)](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/create-container-image.html)

### 4.2 Microservicio (EC2 #2)

```bash
git clone <repo-microservicio>
cd <repo-microservicio>
./mvnw clean package
docker build -t microservicio-orders .
docker run -d -p 8087:8087 --name microservicio microservicio-orders
```

`Dockerfile`:
```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 4.3 BFF (EC2 #1)

```bash
git clone <repo-bff>
cd <repo-bff>
./mvnw clean package
docker build -t bff-pedidos360 .
docker run -d -p 8088:8088 \
  -e ENTRA_ISSUER_URI="https://login.microsoftonline.com/<TENANT_ID>/v2.0" \
  -e ENTRA_API_CLIENT_ID="<API_CLIENT_ID>" \
  -e MICROSERVICE_BASE_URL="http://<IP_PRIVADA_MICROSERVICIO>:8087" \
  --name bff bff-pedidos360
```

`Dockerfile` (mismo patrón, puerto 8088).

> **Importante:** `MICROSERVICE_BASE_URL` usa la **IP privada** de la
> instancia del microservicio, no `localhost` ni la IP pública — el
> tráfico entre ambas instancias viaja por la red interna de AWS.

## 5. Variables de entorno del BFF

| Variable | Valor | Origen |
|---|---|---|
| `ENTRA_ISSUER_URI` | `https://login.microsoftonline.com/<TENANT_ID>/v2.0` | Entra ID → Información general del directorio |
| `ENTRA_API_CLIENT_ID` | Id. de aplicación (cliente) de `api-fullstack` | Entra ID → Registros de aplicaciones |
| `MICROSERVICE_BASE_URL` | `http://<IP_PRIVADA>:8087` | IP privada de la instancia del microservicio |

## 6. Pruebas por capas (antes de tocar API Gateway)

Probar de a una capa a la vez evita mezclar errores de código con errores
de configuración de AWS.

```bash
# 1. Desde la instancia del BFF, contra el microservicio (misma VPC)
curl -i http://<IP_PRIVADA_MICROSERVICIO>:8087/api/orders

# 2. Desde tu máquina local, contra el BFF (sin token) — debe dar 401
curl.exe -i http://<IP_ELASTICA_BFF>:8088/api/orders

# 3. Desde tu máquina local, contra el BFF (con token) — debe dar 200
$tokenPrueba = Read-Host "Pegue el access token de prueba"
curl.exe -i -H "Authorization: Bearer $tokenPrueba" http://<IP_ELASTICA_BFF>:8088/api/orders
Remove-Variable tokenPrueba
```

## 7. API Gateway (API Manager)

1. Crear una **HTTP API** con una ruta `GET /api/orders`.
2. Integración tipo **"URI de HTTP"** apuntando a
   `http://<IP_ELASTICA_BFF>:8088/api/orders`.
3. Configurar un **JWT Authorizer**:
   - Issuer: `https://login.microsoftonline.com/<TENANT_ID>/v2.0`
   - Audience: `api://<API_CLIENT_ID>` (o `<API_CLIENT_ID>` según lo pida el authorizer)

Referencias oficiales:
- Tutorial del curso: "Creando mi Primer API Manager" (material entregado por el docente)
- [Control access to HTTP APIs with JWT authorizers in API Gateway](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-jwt-authorizer.html)

## 8. CORS

Solo aplica en la capa que recibe la petición **directo del navegador**:

- **SPA → API Gateway** (producción/AWS): configurar CORS en el API
  Gateway. Ver [Configuración de CORS para API HTTP](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-cors.html).
- **SPA → BFF directo** (solo para pruebas locales, sin pasar por AWS):
  configurar CORS en Spring Security (`CorsConfigurationSource`), permitiendo
  el origen `http://localhost:5173`.

**API Gateway → BFF** y **BFF → microservicio** son llamadas
servidor-a-servidor: el navegador no interviene, así que CORS no aplica ahí.

## 9. Identidad (Azure Entra ID)

- Registro de la API (`api-fullstack`): expone el scope `access_as_user`.
- Registro de la SPA (`spa-fullstack`): solicita ese scope como permiso delegado.
- El BFF y el API Gateway validan el mismo JWT contra el mismo tenant,
  cada uno de forma independiente.

Referencias oficiales:
- [Registro de una aplicación para Web API](https://learn.microsoft.com/azure/active-directory/develop/scenario-protected-web-api-app-registration)
- [¿Qué es MSAL?](https://learn.microsoft.com/azure/active-directory/develop/msal-overview)
