package com.fonrouge.fullStack.mongoDb

import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.IGroupOfUser
import com.fonrouge.base.model.IRoleInGroup
import com.fonrouge.base.types.OId
import org.litote.kmongo.coroutine.CoroutineCollection

/**
 * Abstract class representing a collection that manages roles within groups.
 *
 * @param GR The type of role in group.
 * @param T The type parameter for the identifier.
 * @param GOU The type of group of user.
 * @param FILT The type of API filter.
 * @param commonContainer The common container to be used in the collection.
 * @param mongoDbBuilder Optional Mongo connection builder forwarded to [Coll]; when `null` (the
 *   default) the collection resolves its database from the process-global Mongo configuration,
 *   preserving prior behavior. Supplying a builder targets a specific server/database (e.g. a
 *   per-test Testcontainers instance).
 */
abstract class IRoleInGroupColl<GR : IRoleInGroup<T, GOU>, T : Any, GOU : IGroupOfUser<*>, FILT : IApiFilter<*>, UID : Any>(
    commonContainer: ICommonContainer<GR, OId<T>, FILT>,
    mongoDbBuilder: MongoDbBuilder? = null,
) : Coll<GR, OId<T>, FILT, UID>(
    commonContainer = commonContainer,
    mongoDbBuilder = mongoDbBuilder,
) {
    override suspend fun CoroutineCollection<GR>.indexes() {
        ensureUniqueIndex(IRoleInGroup<T, GOU>::groupOfUserId, IRoleInGroup<T, GOU>::appRoleId)
    }
}
