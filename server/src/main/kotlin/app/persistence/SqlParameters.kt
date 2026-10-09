package app.persistence

import java.sql.PreparedStatement

/** Bind a composed SQL fragment's values in placeholder order. */
internal fun PreparedStatement.bindParameters(parameters: List<Any?>) {
    parameters.forEachIndexed { index,value -> setObject(index+1,value) }
}
