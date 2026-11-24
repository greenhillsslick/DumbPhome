# dumbOS uses no reflection and no libraries, so the default rules are enough.
# Activities, receivers and the notification listener are referenced from the
# manifest, which R8 keeps automatically.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
