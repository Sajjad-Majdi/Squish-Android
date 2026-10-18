# Pinch uses no reflection-based libraries, so R8 can optimize freely.
-allowaccessmodification
-repackageclasses ''
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkParameterIsNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
}
-assumenosideeffects class android.util.Log { public static int d(...); public static int v(...); }
