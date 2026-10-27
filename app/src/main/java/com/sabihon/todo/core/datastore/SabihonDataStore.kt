package com.sabihon.todo.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * The one DataStore this app has.
 *
 * It lives here rather than in whichever class needed it first because
 * `preferencesDataStore` may only be called **once** per file name: a second declaration
 * for `sabihon_prefs` throws at runtime with "There are multiple DataStores active for
 * the same file". Two feature classes sharing one store is normal; two delegates is not.
 */
internal val Context.sabihonDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sabihon_prefs"
)
