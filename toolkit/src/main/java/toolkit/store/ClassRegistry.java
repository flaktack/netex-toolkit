package toolkit.store;

import java.util.List;

/// **Role: the class ↔ class-idx registry.** Which model classes this database knows, and the
/// two-way mapping between a class, its 2-byte LE idx and the name it is stored under. Key
/// arithmetic that needs nothing but that mapping ([#fullKeyOf]) lives here too.
///
/// It cannot read a row: everything here is a question about the SCHEMA, not the content, and
/// `dbNames` takes a [Txn] only because that table is stored.
public interface ClassRegistry {

    /// The model classes with a class map present in this database (db_names).
    List<Class<?>> dbNames(Txn txn);

    /// 2-byte LE class idx of a registered class; an unregistered one is IllegalArgumentException.
    byte[] classIdx(Class<?> clazz);

    /// int form of [#classIdx].
    int classIdxInt(Class<?> clazz);

    /// Registered class for a class idx; null if unknown.
    Class<?> classForIdx(int idx);

    /// Registered class for a class NAME as stored in the db table; null if unknown.
    Class<?> classForName(String name);

    /// The 8-byte full_key of a row given its class and 4-byte LE local key.
    default long fullKeyOf(Class<?> clazz, byte[] localKey) {
        return toolkit.keycodec.NulKeyCodec.fullKey(classIdxInt(clazz), toolkit.keycodec.NulKeyCodec.u32le(localKey));
    }
}
