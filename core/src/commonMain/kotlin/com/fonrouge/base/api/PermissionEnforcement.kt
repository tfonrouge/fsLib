package com.fonrouge.base.api

import com.fonrouge.base.enums.XEnum
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Declares whether a repository **enforces** role-based CRUD permissions.
 *
 * This is the per-repository "permission posture" declaration (RBAC blueprint D6). It is read at the
 * permission-check sites to decide the **safe default when no resolver is wired**:
 * - [Enforce] (the default): when no permission provider / RBAC backend is configured, **every** remote
 *   (`call != null`) CRUD operation **fails closed** (denies) rather than silently allowing — closing
 *   the former fail-open hole. This covers reads and lists, not only writes: `apiList` and every
 *   `ApiItem.Query` run the same gate, so an unconfigured enforcing repository is fully inaccessible
 *   rather than read-only.
 * - [Off]: the repository is **explicitly non-enforcing** (e.g. an in-memory engine used for samples
 *   and tests, or a deployment whose authorization lives in a layer above). Permission checks return
 *   "allowed", even if a provider happens to be registered.
 *
 * It answers "does this entity require permission checks at all?", which is upstream of and
 * independent from "which component resolves the verdict" — so it is unaffected by where resolution
 * lives (the D5 grant-fetch port).
 *
 * @property encoded the stable serialized representation.
 */
@Serializable
enum class PermissionEnforcement(override val encoded: String) : XEnum {
    /** Enforce permissions; fail **closed** when no resolver is configured. The safe default. */
    @SerialName("E")
    Enforce("E"),

    /** Explicitly non-enforcing; permission checks always return "allowed". */
    @SerialName("O")
    Off("O"),
}
