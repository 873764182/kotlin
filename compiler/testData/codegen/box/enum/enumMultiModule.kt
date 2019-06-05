// WITH_RUNTIME
// MODULE: lib
// FILE: common.kt

enum class FooEnum() {
    OK {
        override fun foo() = "OK"
    },
    FAIL {
        override fun foo() = "FAIL"
    };

    abstract fun foo(): String
}


// MODULE: bar(lib)
// FILE: second.kt

fun bar(): String = FooEnum.valueOf("OK").foo()

// MODULE: main(bar)
// FILE: main.kt

fun box(): String {
    return bar()
}