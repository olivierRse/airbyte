package io.airbyte.integrations.destination.bigquery.migrators

import io.airbyte.integrations.base.destination.typing_deduping.DestinationHandler
import io.airbyte.integrations.base.destination.typing_deduping.DestinationInitialStatus
import io.airbyte.integrations.base.destination.typing_deduping.StreamConfig
import io.airbyte.integrations.base.destination.typing_deduping.migrators.Migration
import io.github.oshai.kotlinlogging.KotlinLogging

class BigqueryAirbyteMetaAndGenerationIdMigration: Migration<BigQueryDestinationState> {
    private val logger = KotlinLogging.logger {}

    override fun migrateIfNecessary(
        destinationHandler: DestinationHandler<BigQueryDestinationState>,
        stream: StreamConfig,
        state: DestinationInitialStatus<BigQueryDestinationState>
    ): Migration.MigrationResult<BigQueryDestinationState> {
        if (!state.initialRawTableStatus.rawTableExists) {
            // The raw table doesn't exist. No migration necessary. Update the state.
            logger.info {
                "Skipping generation_id migration for ${stream.id.originalNamespace}.${stream.id.originalName} because the raw table doesn't exist"
            }
            return Migration.MigrationResult(state.destinationState, false)
        }

        TODO("Not yet implemented")
    }
}
