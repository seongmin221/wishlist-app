package app

import org.flywaydb.core.api.CoreErrorCode
import org.flywaydb.core.api.CoreMigrationType
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.MigrationState
import org.flywaydb.core.api.MigrationVersion
import db.migration.V16__recoverable_read_indexes
import java.lang.reflect.Proxy
import org.flywaydb.core.api.ErrorDetails
import org.flywaydb.core.api.output.ValidateOutput
import org.flywaydb.core.api.output.ValidateResult
import kotlin.test.*

class DatabaseMigrationRetryTest {
    private fun failure(version: String, code: CoreErrorCode) =
        ValidateOutput(version,"migration","migration",ErrorDetails(code,"test failure"))
    private fun validation(vararg errors: ValidateOutput) =
        ValidateResult("test","test",null,false,16,errors.toList(),emptyList())

    private fun retry(result: ValidateResult, state: MigrationState=MigrationState.FAILED,
        resolvedChecksum: Int?=V16__recoverable_read_indexes().checksum,
        appliedChecksum: Int?=V16__recoverable_read_indexes().checksum): Boolean {
        val info=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(MigrationInfo::class.java)) { _,method,_ -> when(method.name) {
            "getVersion" -> MigrationVersion.fromVersion("16")
            "getState" -> state
            "getType" -> CoreMigrationType.JDBC
            "getDescription" -> V16__recoverable_read_indexes().description
            "getResolvedChecksum" -> resolvedChecksum
            "getAppliedChecksum" -> appliedChecksum
            else -> error(method.name)
        } } as MigrationInfo
        return DatabaseMigrationJob.canRetryV16(result,arrayOf(info))
    }
    @Test fun missing_or_changed_v16_cannot_have_its_failure_history_removed() {
        val failure=validation(failure("16",CoreErrorCode.FAILED_VERSIONED_MIGRATION))
        for(state in listOf(MigrationState.MISSING_FAILED,MigrationState.FUTURE_FAILED)) assertFalse(retry(failure,state))
        assertFalse(retry(failure,resolvedChecksum=null))
        assertFalse(retry(failure,appliedChecksum=null))
        assertFalse(retry(failure,appliedChecksum=123))
        assertFalse(DatabaseMigrationJob.canRetryV16(failure,emptyArray()))
    }
    @Test fun only_failed_v16_is_retryable() {
        assertTrue(retry(validation(failure("16",CoreErrorCode.FAILED_VERSIONED_MIGRATION))))
        assertFalse(retry(validation()))
        assertFalse(retry(validation(failure("15",CoreErrorCode.FAILED_VERSIONED_MIGRATION))))
    }
    @Test fun retry_cannot_silently_realign_other_migration_checksums_or_remove_missing_migrations() {
        for(code in listOf(CoreErrorCode.CHECKSUM_MISMATCH,CoreErrorCode.DESCRIPTION_MISMATCH,
            CoreErrorCode.TYPE_MISMATCH,CoreErrorCode.APPLIED_VERSIONED_MIGRATION_NOT_RESOLVED)) {
            assertFalse(retry(validation(
                failure("16",CoreErrorCode.FAILED_VERSIONED_MIGRATION),failure("15",code))))
            assertFalse(retry(validation(failure("16",code))))
        }
    }
}
