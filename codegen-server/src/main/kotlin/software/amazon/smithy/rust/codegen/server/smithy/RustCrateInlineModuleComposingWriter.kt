package software.amazon.smithy.rust.codegen.server.smithy

import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.comment
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import java.util.concurrent.ConcurrentHashMap

typealias DocWriter = () -> Unit

/**
 * Creates a sequence of `Writable`s, each of which need to write in the same `rust_module::inline_module`. Calling
 * `render()`, creates the inline module and then invokes all of the writables.
 */
private class RustCrateInlineModuleComposingWriter {
    companion object {
        // A map of RustCrate to the InlineModuleWriter that has been created for that RustCrate
        private val crateToInlineModule: ConcurrentHashMap<RustCrate, RustCrateInlineModuleComposingWriter> =
            ConcurrentHashMap()

        /**
         * If the given shape is an "overridden constrained member" shape, then it registers
         * the writable to be called later on at the time of rendering. However, if the shape
         * is not an "overridden constrained member" then it calls the `rustWriterCreator` function
         * with the given writable. The `rustWriterCreator` can be:
         *
         * RustCrate::withModule
         * RustCrate::useShapeWriter
         */
        fun invokeWritableOrComposeIfConstrainedStructureMember(
            rustCrate: RustCrate,
            maybeConstrainedMemberShape: Shape,
            codegenContext: ServerCodegenContext,
            docWriter: DocWriter?,
            codeWritable: Writable,
            rustWriterCreator: (Writable) -> Unit,
        ) {
            // All structure constrained-member-shapes code is generated inside the structure builder's module.
            val parentAndInlineModuleInfo =
                maybeConstrainedMemberShape.getParentAndInlineModuleForConstrainedMember(codegenContext.symbolProvider)
            if (parentAndInlineModuleInfo == null) {
                docWriter?.invoke()
                rustWriterCreator(codeWritable)
            } else {
                val (parent, inline) = parentAndInlineModuleInfo
                val inlineWriter = crateToInlineModule.getOrPut(rustCrate) { RustCrateInlineModuleComposingWriter() }
                // Ensure the parent module exists by creating a RustWriter on it.
                rustCrate.withModule(parent) {
                    // Register the writable to be called later on at rendering time.
                    inlineWriter.add(inline.parent, inline, debugTrace(codegenContext), docWriter) {
                        codeWritable(this)
                    }
                }
            }
        }

        fun debugTrace(codegenContext: ServerCodegenContext): String? =
            if (codegenContext.settings.codegenConfig.debugMode) {
                Thread.currentThread().stackTrace.first { it.fileName != "Thread.java" && it.fileName != "RustCrateInlineModuleWriter.kt" }
                    .let {
                        "/* ${it.fileName}:${it.lineNumber} */"
                    }
            } else {
                null
            }

        fun renderAndRemoveCrate(rustCrate: RustCrate) {
            val composingWriter = crateToInlineModule.remove(rustCrate)
            composingWriter?.render(rustCrate)
        }

        fun addInlineModuleInCrate(
            rustCrate: RustCrate,
            codegenContext: ServerCodegenContext,
            inlineModule: RustModule.LeafModule,
            docWriter: DocWriter? = null,
            writable: Writable,
        ) {
            val crateInlineWriter = crateToInlineModule.getOrPut(rustCrate) { RustCrateInlineModuleComposingWriter() }
            crateInlineWriter.add(inlineModule.parent, inlineModule, debugTrace(codegenContext), docWriter) {
                writable(this)
            }
        }
    }

    data class InlineCodeWritable(val writable: Writable, val callStack: String?)
    data class InlineCodeAndDocWritable(
        val docWritables: MutableList<DocWriter>,
        val codeWritables: MutableList<InlineCodeWritable>,
    )

    /**
     * Keeps a mapping of InlineModule -> Writables that are to be called at render time.
     */
    class InlineModules {
        private val inlineModuleWritables: HashMap<RustModule.LeafModule, InlineCodeAndDocWritable> = HashMap()

        /**
         * Adds a `Writable` to the inline module
         */
        fun addWritable(inlineModule: RustModule.LeafModule, docWriter: DocWriter?, writable: Writable, debug: String?) {
            val inlineModule = inlineModuleWritables.getOrPut(inlineModule) { InlineCodeAndDocWritable(mutableListOf(), mutableListOf()) }
            if (docWriter != null) {
                inlineModule.docWritables.add(docWriter)
            }
            inlineModule.codeWritables.add(InlineCodeWritable(writable, debug))
        }

        /**
         * For each registered inline module, writes out a `mod {inline_module_name}` statement inside the
         * parent module, and then calls each of the registered writable.
         */
        fun render(writer: RustWriter) {
            for ((inlineModule, generators) in inlineModuleWritables) {
                // Write the doc strings that need to go on top of the inline module
                for (docWritable in generators.docWritables) {
                    docWritable()
                }

                // Begin a rust inline module, write out the debug string and call each of the writable.
                writer.withInlineModule(inlineModule) {
                    for (w in generators.codeWritables) {
                        if (w.callStack != null) {
                            this.comment(w.callStack)
                        }
                        w.writable(this)
                    }
                }
            }
        }
    }

    // A mapping of Rust Module -> InlineModules
    private val modules: HashMap<RustModule, InlineModules> = HashMap()

    /**
     * Registers the given writable to be rendered later inside `fileModule::inlineModule`
     */
    fun add(
        fileModule: RustModule,
        inlineModule: RustModule.LeafModule,
        debug: String?,
        docWriter: DocWriter? = null,
        writable: Writable,
    ): RustCrateInlineModuleComposingWriter {
        modules.getOrPut(fileModule) { InlineModules() }.addWritable(inlineModule, docWriter, writable, debug)
        return this
    }

    /**
     * Calls each `Writable` that has been registered for `parent::inline_module`
     */
    fun render(rustCrate: RustCrate) {
        for ((module, inlineModules) in modules) {
            rustCrate.withModule(module) {
                inlineModules.render(this)
            }
        }
    }
}


fun RustCrate.withModuleOrWithStructureBuilder(
    module: RustModule,
    shape: Shape,
    codegenContext: ServerCodegenContext,
    docWriter: DocWriter? = null,
    codeWritable: Writable,
) {
    RustCrateInlineModuleComposingWriter.invokeWritableOrComposeIfConstrainedStructureMember(
        this,
        shape,
        codegenContext,
        docWriter,
        codeWritable,
    ) {
        this.withModule(module, it)
    }
}

fun RustCrate.useShapeWriterOrUseWithStructureBuilder(
    shape: Shape,
    codegenContext: ServerCodegenContext,
    docWriter: DocWriter? = null,
    writable: Writable,
) {
    RustCrateInlineModuleComposingWriter.invokeWritableOrComposeIfConstrainedStructureMember(
        this,
        shape,
        codegenContext,
        docWriter,
        writable,
    ) {
        this.useShapeWriter(shape, it)
    }
}

fun RustCrate.withComposableInlineModule(
    inlineModule: RustModule.LeafModule,
    codegenContext: ServerCodegenContext,
    docWriter: DocWriter? = null,
    codeWritable: Writable,
) {
    check(inlineModule.isInline()) {
        "module has to be an inline module for it to be used with the InlineModuleWriter"
    }
    RustCrateInlineModuleComposingWriter.addInlineModuleInCrate(
        this,
        codegenContext,
        inlineModule,
        docWriter,
        codeWritable,
    )
}

fun RustCrate.renderComposableInlineModules() {
    RustCrateInlineModuleComposingWriter.renderAndRemoveCrate(this)
}
