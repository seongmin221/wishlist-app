package app

import org.flywaydb.core.api.CoreErrorCode
import org.flywaydb.core.api.CoreMigrationType
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.MigrationState
import db.migration.V16__recoverable_read_indexes
import org.flywaydb.core.api.output.ValidateResult

/** A deployment job, never called by API/Worker startup. Run only one job per database. */
internal object DatabaseMigrationJob {
    fun run(url: String, username: String, password: String) {
        val flyway=DatabaseFactory.migrationConfiguration(url,username,password)
            .ignoreMigrationPatterns("*:pending").load()
        val validation=flyway.validateWithResult()
        if(!validation.validationSuccessful) {
            check(canRetryV16(validation,flyway.info().all())) { validation.allErrorMessages }
            // Repair can also realign checksums/remove missing migrations. The guard excludes those errors.
            flyway.repair()
            flyway.validate()
        }
        flyway.migrate()
        flyway.validate()
    }

    internal fun canRetryV16(validation: ValidateResult, migrations: Array<MigrationInfo>): Boolean {
        if(validation.validationSuccessful || validation.invalidMigrations.isEmpty() ||
            validation.invalidMigrations.any {
                it.version!="16" || it.errorDetails.errorCode!=CoreErrorCode.FAILED_VERSIONED_MIGRATION
            }) return false
        val failed=migrations.singleOrNull { it.version?.toString()=="16" } ?: return false
        val expected=V16__recoverable_read_indexes()
        // Missing/future failures share the validation error code; FAILED guarantees a resolved migration.
        // Flyway 11.20 matching helpers accept null checksums, so compare actual values explicitly.
        return failed.state==MigrationState.FAILED && failed.type==CoreMigrationType.JDBC &&
            failed.description==expected.description && failed.resolvedChecksum==expected.checksum &&
            failed.appliedChecksum==expected.checksum
    }

}

fun main() {
    val env=System.getenv()
    DatabaseMigrationJob.run(env.getValue("DATABASE_URL"),env.getValue("DATABASE_USER"),env.getValue("DATABASE_PASSWORD"))
}
