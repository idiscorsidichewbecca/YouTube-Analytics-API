# Meta integration report

## Added without changing existing YouTube mappings

- Meta OAuth and encrypted credentials persistence.
- Facebook analytics controller: 12 endpoints.
- Instagram analytics controller: 10 endpoints.
- Combined Meta overview: 1 endpoint.
- Flyway migration `V2__create_meta_credentials.sql`.
- Graph API version configurable, default `v25.0`.
- Updated Swagger/OpenAPI and GPT Actions contract.
- Optional feature flag `META_ENABLED`, default `false`.

## Release checklist

1. Set the Meta environment variables listed in `.env.example`.
2. Register the exact production callback `/oauth2/meta/callback` in the Meta App.
3. Deploy; Flyway creates `meta_credentials` automatically.
4. Open `/admin/meta/connect` with the administrator Basic credentials.
5. Check `/admin/meta/status`.
6. Test `/api/v1/meta/overview` using the Bearer API key.
7. Repeat the Meta connection before the stored 60-day token expiry.

## Validation performed in the delivery environment

- All 24 new Meta Java source files compile with Java 21 against framework API stubs.
- Date-range behavior was executed with a deterministic clock.
- Both OpenAPI copies parse successfully as YAML.
- All 39 OpenAPI operation IDs are unique and match controller annotations.
- All local OpenAPI `$ref` references resolve.

A complete Maven build was not executable in the delivery container because Maven dependencies were not cached and outbound dependency downloads were unavailable. Run `./mvnw clean test` in the normal development or CI environment before production deployment.
