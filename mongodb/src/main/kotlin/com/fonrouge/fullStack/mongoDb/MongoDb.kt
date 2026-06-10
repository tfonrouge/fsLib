package com.fonrouge.fullStack.mongoDb

import com.fonrouge.base.serializers.*
import com.mongodb.client.model.Collation
import com.mongodb.client.model.CollationStrength
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoDatabase
import io.ktor.server.application.*
import org.litote.kmongo.reactivestreams.KMongo.createClient
import org.litote.kmongo.serialization.registerSerializer
import java.util.*
import java.util.concurrent.ConcurrentHashMap

internal var mongoDbBuilder: MongoDbBuilder = MongoDbBuilder()

/**
 * Lazily initialized variable to hold the MongoDB client instance.
 *
 * Resolved through [MongoDbBuilder.sharedClient], so it reuses the same process-wide
 * client as any [Coll] configured with a [MongoDbBuilder] targeting the same
 * connection string (from the mongoDbPluginConfiguration).
 */
val mongoClient by lazy {
    MongoDbBuilder.sharedClient(mongoDbBuilder.connectionString)
}

/**
 * Represents a lazily initialized instance of the MongoDatabase.
 * This variable is used to interact with the MongoDB database specified
 * by the configuration.
 *
 * The database instance is retrieved using the MongoClient and the database name
 * configured in the mongoDbPluginConfiguration.
 */
val mongoDatabase: MongoDatabase by lazy {
    mongoClient.getDatabase(mongoDbBuilder.database)
}

/**
 * Creates a `Collation` object with the specified locale and collation strength.
 *
 * @param locale The locale to be used for collation. Defaults to the locale specified in mongoDbPluginConfiguration.
 * @param collationStrength The strength to be used for collation. Defaults to `CollationStrength.PRIMARY`.
 * @return A `Collation` object configured with the given locale and collation strength.
 */
fun collation(
    locale: String = mongoDbBuilder.locale,
    collationStrength: CollationStrength = CollationStrength.PRIMARY,
): Collation = Collation.builder().locale(locale).collationStrength(collationStrength).build()

/**
 * MongoDbPlugin is an application plugin for integrating MongoDB with the application.
 * It sets up the required configuration for connecting to a MongoDB database.
 *
 * The plugin uses the `MongoDbPluginConfiguration` class to gather necessary configurations
 * like server details, authentication information, and the target database.
 *
 * The plugin outputs a confirmation message when it is successfully installed.
 */
@Suppress("unused")
val MongoDbPlugin = createApplicationPlugin(
    name = "MongoDbPlugin",
    createConfiguration = ::MongoDbBuilder
) {
    mongoDbBuilder = pluginConfig
    println("MongoDbPlugin is installed: host=${mongoDbBuilder.serverUrl}, database=${mongoDbBuilder.database}")
}

/**
 * This class provides configuration settings for the MongoDB plugin.
 * It includes properties for specifying server details, authentication information,
 * the target database, and locale settings.
 *
 * @property serverUrl The URL of the MongoDB server. Defaults to "localhost".
 * @property serverPort The port number on which the MongoDB server is running. Defaults to 27017.
 * @property authSource The database to use for authentication. Defaults to null.
 * @property user The username for authentication. Defaults to null.
 * @property password The password for authentication. Defaults to null.
 * @property database The name of the database to connect to. Defaults to "test".
 * @property locale The locale setting to use. Defaults to the default locale language.
 * @property connectionString The constructed MongoDB connection string based on the provided configurations.
 */
data class MongoDbBuilder(
    var serverUrl: String? = "localhost",
    var serverPort: Int = 27017,
    var authSource: String? = null,
    var user: String? = null,
    var password: String? = null,
    var database: String = "test",
    var locale: String = Locale.getDefault().language,
) {

    val connectionString
        get() = "mongodb://" + if (user != null || password != null) {
            "$user:$password@"
        } else {
            ""
        }.let {
            "$it$serverUrl:$serverPort" + if (authSource != null) "/?authSource=$authSource" else ""
        }

    /**
     * Returns the [MongoDatabase] named [database], obtained from the process-wide shared
     * [MongoClient] for this builder's [connectionString] (see [sharedClient]).
     *
     * Repeated calls — and distinct builders targeting the same server — reuse the same
     * underlying client (and thus one connection pool and one set of monitor threads);
     * only the database handle differs. The [database] name is intentionally not part of
     * the client cache key, so callers can hold many databases on one client.
     */
    fun getMongoDb(): MongoDatabase = sharedClient(connectionString).getDatabase(database)

    companion object {

        /**
         * Process-wide cache of [MongoClient] instances keyed by connection string.
         *
         * A `MongoClient` is thread-safe, owns a connection pool plus monitor threads, and the
         * driver recommends one instance per cluster — so every [getMongoDb] call (one per
         * builder-configured [Coll]) must reuse it rather than construct a fresh, never-closed
         * client. Cached clients live for the lifetime of the process and are never closed,
         * matching the prior behavior of the global [mongoClient] singleton.
         */
        private val clientCache = ConcurrentHashMap<String, MongoClient>()

        /**
         * Returns the shared [MongoClient] for [connectionString], creating and caching it on
         * first use. Internal so the global [mongoClient] lazy routes through the same cache.
         *
         * @param connectionString The full MongoDB connection string identifying the cluster
         * (host, port, credentials, authSource — but not the database name).
         * @return The process-wide client for that connection string.
         */
        internal fun sharedClient(connectionString: String): MongoClient =
            clientCache.computeIfAbsent(connectionString) { createClient(it) }

        init {
            //
            // TODO: using registerModule doesn't encode LocalDate to 'YYYY-MM-YY' as per FSLocalDateSerializer
            // val d1: java.time.LocalDate
            // d1.json -> { "$date" : "2024-09-01T00:00:00Z" }
            //
            // registerModule(serializersModule)
            registerSerializer(OIdSerializer)
            registerSerializer(StringIdSerializer)
            registerSerializer(IntIdSerializer)
            registerSerializer(LongIdSerializer)
            registerSerializer(FSOffsetDateTimeSerializer)
            registerSerializer(FSLocalDateSerializer)
            registerSerializer(FSLocalDateTimeSerializer)
            // TODO: evaluate registering via registerModule(serializersModule) instead of individual registerSerializer calls,
            //  or implementing a custom BsonCodec at the MongoDB driver level for robust numeric type coercion
            registerSerializer(FSNumberDoubleSerializer)
        }
    }
}
