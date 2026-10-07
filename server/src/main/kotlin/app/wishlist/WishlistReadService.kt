package app.wishlist

import app.persistence.inTransaction
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class WishlistReadService(private val dataSource: DataSource) {
    private val repository=WishlistReadRepository()
    fun read(owner: UUID, query: ReadQuery): ReadResult = dataSource.inTransaction(readOnly=true) { c ->
        when(repository.validateScope(c,owner,query.scope)) {
            ReadScopeAvailability.CATEGORY_NOT_FOUND -> return@inTransaction ReadResult.CategoryNotFound
            ReadScopeAvailability.PURPOSE_NOT_FOUND -> return@inTransaction ReadResult.PurposeNotFound
            ReadScopeAvailability.AVAILABLE -> Unit
        }
        val count=repository.count(c,owner,query.scope)
        ReadResult.Success(when(val window=query.window) {
            is ReadWindow.Page -> page(c,owner,query.scope,window,count)
            is ReadWindow.Anchor -> anchor(c,owner,query.scope,window,count)
        })
    }
    private fun page(c: Connection, owner: UUID, scope: ReadScope, window: ReadWindow.Page, count: Long): ReadPage {
        require(window.limit in 1..100)
        val fetched=repository.keys(c,owner,scope,window.boundary,window.direction,window.limit+1)
        val selected=fetched.take(window.limit).let { if(window.direction==ReadDirection.NEWER) it.asReversed() else it }
        if(selected.isEmpty()) return ReadPage(emptyList(),count,null,null,null,null,null)
        val previous=if(window.direction==ReadDirection.NEWER) fetched.size>window.limit else repository.exists(c,owner,scope,selected.first(),ReadDirection.NEWER)
        val next=if(window.direction==ReadDirection.OLDER) fetched.size>window.limit else repository.exists(c,owner,scope,selected.last(),ReadDirection.OLDER)
        return ReadPage(repository.load(c,owner,selected),count,selected.first().takeIf { previous },selected.last().takeIf { next },null,null,null)
    }
    private fun anchor(c: Connection, owner: UUID, scope: ReadScope, window: ReadWindow.Anchor, count: Long): ReadPage {
        require(window.before in 0..20 && window.after in 0..20)
        val original=repository.findAnchor(c,owner,scope,window.position.id)
        val resolved=original ?: repository.keys(c,owner,scope,window.position,ReadDirection.OLDER,1).firstOrNull()
            ?: repository.keys(c,owner,scope,window.position,ReadDirection.NEWER,1).firstOrNull()
        if(resolved==null) return ReadPage(emptyList(),count,null,null,window.position.id,null,false)
        val before=repository.keys(c,owner,scope,resolved,ReadDirection.NEWER,window.before+1)
        val after=repository.keys(c,owner,scope,resolved,ReadDirection.OLDER,window.after+1)
        val selected=before.take(window.before).asReversed()+resolved+after.take(window.after)
        return ReadPage(repository.load(c,owner,selected),count,selected.first().takeIf { before.size>window.before },
            selected.last().takeIf { after.size>window.after },window.position.id,resolved.id,original!=null)
    }
}
