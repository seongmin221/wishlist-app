package app.persistence

import java.sql.Connection
import java.util.UUID

object OwnerStructureLock {
    fun lock(connection: Connection, ownerId: UUID, skipLocked: Boolean = false): Boolean {
        require(!connection.autoCommit)
        val exists=if(skipLocked) connection.prepareStatement("select id from app_users where id=?").use { s ->
            s.setObject(1,ownerId);s.executeQuery().use { it.next() }
        } else false
        if(!exists) connection.prepareStatement("insert into app_users(id) values (?) on conflict(id) do nothing").use { s ->
            s.setObject(1, ownerId); s.executeUpdate()
        }
        return connection.prepareStatement("select id from app_users where id=? for update ${if (skipLocked) "skip locked" else ""}").use { s ->
            s.setObject(1, ownerId); s.executeQuery().use { it.next() }
        }
    }
}
