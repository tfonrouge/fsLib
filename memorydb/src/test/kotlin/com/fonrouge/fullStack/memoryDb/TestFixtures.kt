package com.fonrouge.fullStack.memoryDb

import com.fonrouge.base.api.ApiFilter
import com.fonrouge.base.common.ICommonContainer
import com.fonrouge.base.model.BaseDoc
import kotlinx.serialization.Serializable

/**
 * Simple test model for InMemoryRepository tests.
 */
@Serializable
data class TestItem(
    override val _id: String = "",
    val name: String = "",
    val price: Double = 0.0,
    val category: String = "",
    val active: Boolean = true,
) : BaseDoc<String>

/**
 * Common container providing metadata for [TestItem].
 */
object CommonTestItem : ICommonContainer<TestItem, String, ApiFilter>(
    itemKClass = TestItem::class,
    filterKClass = ApiFilter::class,
    labelItem = "Item",
    labelList = "Items",
)

/**
 * Parent entity for dependency-checking tests (the entity being deleted).
 */
@Serializable
data class ParentItem(
    override val _id: String = "",
    val name: String = "",
) : BaseDoc<String>

/**
 * Common container providing metadata for [ParentItem].
 */
object CommonParentItem : ICommonContainer<ParentItem, String, ApiFilter>(
    itemKClass = ParentItem::class,
    filterKClass = ApiFilter::class,
    labelItem = "Parent",
    labelList = "Parents",
)

/**
 * Child entity referencing a [ParentItem] via [parentId]; used to prove that deleting a parent
 * with existing children is refused.
 */
@Serializable
data class ChildItem(
    override val _id: String = "",
    val parentId: String = "",
) : BaseDoc<String>

/**
 * Common container providing metadata for [ChildItem].
 */
object CommonChildItem : ICommonContainer<ChildItem, String, ApiFilter>(
    itemKClass = ChildItem::class,
    filterKClass = ApiFilter::class,
    labelItem = "Child",
    labelList = "Children",
)
