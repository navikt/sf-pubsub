# sf-pubsub

Integrasjonsapp for å speile data fra Salesforce Platform Events og Change Data Capture Events til systemer som Kafka, Prometheus, Kibana osv. via Pub/Sub API.

Eksempel på oppsett for å lytte til Salesforce Topic og publisere på Aiven Kafka:

1. Sørg for at Pub/Sub-integrasjonen har de nødvendige tillatelsene for å lese Salesforce Topic.

2. Finn navnet på Salesforce Topic. Eksempler:
   - Change Data Capture event for et custom object `Something` blir `/data/Something__ChangeEvent`
   - Change Data Capture event for et native object `Task` blir `/data/TaskChangeEvent`
   - Et custom Platform event `BjornMessage__c` blir `/event/BjornMessage__e`

3. Hvis du skal publisere til en Kafka på Aiven, lag en topic yaml-fil under [./topic](https://github.com/navikt/sf-pubsub/tree/main/.topic).
   Navnekonvensjon og mappestruktur er `./topic/<namespace>/<dev/prod>/<topic-navn>.yaml`. Denne vil publiseres ved push til repoet.
   For forhåndsprovisjonering kan du også kjøre:
   ```shell
   kubectl apply -f <topic-yaml-fil> --context <dev-gcp/prod-gcp> -n <namespace>
    ```

4. Opprett en mappe under [.nais/](https://github.com/navikt/sf-pubsub/tree/main/.nais) med navnet til appen som lytter på Salesforce Topic. Navnekonvensjonen er prefikset sf-pubsub- etterfulgt av entitetsnavnet, f.eks. sf-pubsub-task. Legg inn dev-gcp.yaml og prod-gcp.yaml med nais-konfigurasjon for hvert miljø. Grunnkonfigurasjonen finnes i default-mappen, så du trenger kun konfigurasjon spesifikt for din app. Eksempel:

 ```yaml
spec:
   ingresses:
      - "https://sf-pubsub-bjornmessage.intern.dev.nav.no"  # For å visuelt se whitelist-effekten i browser under /gui
   env:
      - name: WHITELIST_FILE
        value: '/whitelist/bjorn-message/dev.json'  # Bruk whitelist for å redusere event før videresending
      - name: KAFKA_TOPIC
        value: 'teamcrm.bjorn-message'  # Fullt navn på Kafka-topic: <namespace>.<topic-name>
      - name: SALESFORCE_TOPIC
        value: '/event/BjornMessage__e'  # Navn på Salesforce Topic
 ```

5. Oppdater [Bootstrap.kt](https://github.com/navikt/sf-pubsub/blob/main/src/main/kotlin/no/nav/sf/pubsub/Bootstrap.kt) med din app for å styre logikken for hvert Salesforce-event. Du kan lage en egen record handler eller bruke changeDataCaptureKafkaRecordHandler til å videresende hver payload, redusert basert på `WHITELIST_FILE` og med Id fra ChangeEventHeader som ID på Kafka-record.

6. Hvis whitelist brukes, plasseres en json-fil under mappen `resources/whitelist/<entity-navn>/<dev/prod>.json`. Whitelist-json
skal kun bestå av objekter og streng-primitive verdier. Angi feltene du vil inkludere med:
   - `ALL` for å inkludere hele feltet (enkeltverdi, objekt eller array)
   - `DATE_FROM_MILLIS` hvis du forventer en dato som kommer i Millis-format (long), for konvertering til ISO UTC

7. Oppdater .github/workflows/main.yml med appen du vil deploye og hvilket cluster den skal deployes til. [main.yml](https://github.com/navikt/sf-pubsub/blob/main/.github/workflows/main.yml) styrer hva som deployes og til hvilket cluster, uavhengig av hvilken branch du er i.
