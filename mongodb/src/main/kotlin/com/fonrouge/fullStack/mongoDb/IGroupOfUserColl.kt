package com.fonrouge.fullStack.mongoDb

import com.fonrouge.base.api.IApiFilter
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.IGroupOfUser
import com.fonrouge.base.types.OId
import org.litote.kmongo.coroutine.CoroutineCollection

/**
 * Abstract class representing a collection of user groups with specific filter options.
 *
 * @param CC The type parameter for the common container used by the group collection.
 * @param GOU The type parameter representing the group of users.
 * @param T The type parameter representing any element associated with the group.
 * @param FILT The type parameter for the API filter used in the group collection.
 * @property commonContainer The common container instance for the group collection.
 * @param mongoDbBuilder Optional Mongo connection builder forwarded to [Coll]; when `null` (the
 *   default) the collection resolves its database from the process-global Mongo configuration,
 *   preserving prior behavior. Supplying a builder targets a specific server/database (e.g. a
 *   per-test Testcontainers instance).
 */
@Suppress("unused")
abstract class IGroupOfUserColl<GOU : IGroupOfUser<T>, T : Any, FILT : IApiFilter<*>, UID : Any>(
    commonContainer: ICommonContainer<GOU, OId<T>, FILT>,
    mongoDbBuilder: MongoDbBuilder? = null,
) : Coll<GOU, OId<T>, FILT, UID>(
    commonContainer = commonContainer,
    mongoDbBuilder = mongoDbBuilder,
) {
    override suspend fun CoroutineCollection<GOU>.indexes() {
        ensureUniqueIndex(IGroupOfUser<T>::description)
    }
}
