# PoC: Sidecar de OAuth2/OIDC na frente de um CRUD

Objetivo: provar que dá para tirar toda a responsabilidade de autenticação/
autorização do microserviço de negócio e concentrá-la em um **sidecar**, que:

1. Intercepta **todas** as chamadas ao CRUD.
2. Para `GET`/`HEAD` exige o escopo `read` no access token.
3. Para `POST`/`PUT`/`PATCH`/`DELETE` exige o escopo `write`.
4. Se o usuário não está autenticado → inicia o Authorization Code Flow (redireciona ao Keycloak).
5. Se está autenticado mas falta o escopo necessário → **reinicia** o fluxo OAuth pedindo consentimento adicional (step-up), em vez de simplesmente devolver 403.

```
Browser/Client ──► oauth-sidecar (8082) ──► crud-service (8081, não exposto)
                         │
                         └──► Keycloak (8080) — login / emissão de token
```

O `crud-service` não conhece OAuth: ele só existe na rede interna do
docker-compose, e nunca é publicado no host. Essa é a demonstração central
do padrão sidecar.

## 1. Pré-requisito: hostname `keycloak`

Para o Keycloak funcionar igual tanto para o navegador (rodando no seu host)
quanto para os containers (rede interna do Docker), os dois lados precisam
enxergar o Keycloak pelo **mesmo** hostname: `keycloak`.

Adicione uma entrada no seu arquivo de hosts apontando para `127.0.0.1`:

- Linux/Mac: `/etc/hosts`
- Windows: `C:\Windows\System32\drivers\etc\hosts`

```
127.0.0.1 keycloak
```

Sem isso, o navegador não conseguirá resolver `http://keycloak:8080/...`
durante o redirect de login.

## 2. Subir o ambiente

```bash
cd oauth-sidecar-poc
docker compose up --build
```

Aguarde o Keycloak ficar saudável (o `oauth-sidecar` depende disso) e o
realm `poc-realm` ser importado automaticamente a partir de
`keycloak/realm-export.json`.

Usuário de teste já provisionado:

- **username:** `demo`
- **senha:** `demo123`

## 3. Testando o fluxo

Abra o navegador (importante: usar o navegador, pois o fluxo envolve
redirect + tela de login/consentimento do Keycloak):

```
http://localhost:8082/api/tasks
```

- Você será redirecionado para o Keycloak para logar como `demo`.
- Na tela de consentimento, o escopo **write** aparece como opcional —
  **desmarque-o** para simular um usuário que só tem permissão de leitura.
- Após o login, a listagem de tasks (`GET`) funciona normalmente porque o
  escopo `read` é concedido por padrão.

Agora tente uma escrita, por exemplo via um formulário/REST client
autenticado na mesma sessão do navegador, ou simplesmente acesse uma rota
de escrita — como o teste de POST/PUT precisa de corpo, use uma extensão
tipo "Requestly"/Postman com a sessão do navegador, ou curl com a cookie
de sessão copiada. Para simplificar a demo, a forma mais direta é:

1. Tente `PUT http://localhost:8082/api/tasks/1` sem o escopo `write`.
2. O sidecar detecta que falta `write` no token e responde com um
   **redirect para `/oauth2/authorization/keycloak?reauth=true`**.
3. Isso reabre a tela de consentimento do Keycloak (`prompt=consent`),
   agora permitindo marcar o escopo `write`.
4. Após aprovar, repita a chamada — agora ela é encaminhada ao
   `crud-service` normalmente.

Endpoints do CRUD (todos por trás de `/api`, proxiados para `crud-service`):

| Método | Rota              | Escopo exigido |
|--------|-------------------|----------------|
| GET    | `/api/tasks`      | read           |
| GET    | `/api/tasks/{id}` | read           |
| POST   | `/api/tasks`      | write          |
| PUT    | `/api/tasks/{id}` | write          |
| DELETE | `/api/tasks/{id}` | write          |

Exemplo de corpo para POST/PUT:

```json
{ "title": "Estudar sidecar OAuth", "description": "PoC", "done": false }
```

## 4. Onde olhar o código

- `oauth-sidecar/.../config/SecurityConfig.java` — exige login OAuth2 em
  toda rota (exceto `/actuator/health`); é o que dispara o fluxo OAuth
  automaticamente para usuários não autenticados.
- `oauth-sidecar/.../config/StepUpAuthorizationRequestResolver.java` —
  injeta `prompt=consent` quando pedimos reautorização por escopo faltante.
- `oauth-sidecar/.../proxy/ProxyController.java` — é o **interceptor**:
  decide o escopo exigido por método HTTP, valida contra o access token e
  faz o proxy para o `crud-service`, ou dispara o step-up de OAuth.
- `crud-service/.../controller/TaskController.java` — CRUD puro, sem
  nenhuma linha de código de segurança.
- `keycloak/realm-export.json` — client `sidecar-client`, escopos `read`
  (padrão, silencioso) e `write` (opcional, aparece na tela de consentimento).

## 5. Simplificações desta PoC (documentadas de propósito)

- `H2` em memória no `crud-service` — dados somem a cada restart.
- CSRF desabilitado no sidecar só para facilitar testes com curl/Postman.
- `sslRequired: none` no Keycloak — nunca faça isso fora de ambiente local.
- O token de acesso não é repassado ao `crud-service` (ele não teria como
  validar); em vez disso propagamos identidade via headers
  `X-Auth-User` / `X-Auth-Scopes` — um passo further seria assinar/cifrar
  esses headers ou usar mTLS entre sidecar e serviço.
- Sem refresh automático de token expirado nesta versão — para produção,
  o `spring-boot-starter-oauth2-client` já dá suporte a isso via
  `OAuth2AuthorizedClientManager` com `refresh_token`.
