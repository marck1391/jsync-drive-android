# The node (a Go program) and the DocumentsProvider are started by name from the manifest and the app; R8 keeps what
# the manifest references. Nothing in the app uses reflection.
-keepattributes SourceFile,LineNumberTable
