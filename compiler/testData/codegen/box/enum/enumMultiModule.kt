// WITH_RUNTIME
// MODULE: lib
// FILE: common.kt

enum class FooEnum(val s: String) {
    OK("OK"),
    FAIL("FAIL");

    fun foo() = s
}


// MODULE: bar(lib)
// FILE: second.kt

fun bar(): String = FooEnum.valueOf("OK").foo()

// MODULE: main(bar)
// FILE: main.kt

fun box(): String {
    return bar()
}