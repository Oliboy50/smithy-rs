/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rustsdk.customize.s3

import software.amazon.smithy.aws.traits.protocols.RestXmlTrait
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.customize.ClientCodegenDecorator
import software.amazon.smithy.rust.codegen.client.smithy.generators.protocol.ClientProtocolGenerator
import software.amazon.smithy.rust.codegen.client.smithy.protocols.ClientRestXmlFactory
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.protocols.ProtocolMap
import software.amazon.smithy.rust.codegen.core.smithy.protocols.RestXml
import software.amazon.smithy.rust.codegen.core.smithy.traits.AllowInvalidXmlRoot
import software.amazon.smithy.rust.codegen.core.util.letIf
import java.util.logging.Logger

/**
 * Top level decorator for S3
 */
class S3Decorator : ClientCodegenDecorator {
    override val name: String = "S3"
    override val order: Byte = 0
    private val logger: Logger = Logger.getLogger(javaClass.name)
    private val invalidXmlRootAllowList = setOf(
        // API returns GetObjectAttributes_Response_ instead of Output
        ShapeId.from("com.amazonaws.s3#GetObjectAttributesOutput"),
    )

    override fun protocols(
        serviceId: ShapeId,
        currentProtocols: ProtocolMap<ClientProtocolGenerator, ClientCodegenContext>,
    ): ProtocolMap<ClientProtocolGenerator, ClientCodegenContext> = currentProtocols + mapOf(
        RestXmlTrait.ID to ClientRestXmlFactory { protocolConfig ->
            S3ProtocolOverride(protocolConfig)
        },
    )

    override fun transformModel(service: ServiceShape, model: Model): Model =
        ModelTransformer.create().mapShapes(model) { shape ->
            shape.letIf(isInInvalidXmlRootAllowList(shape)) {
                logger.info("Adding AllowInvalidXmlRoot trait to $it")
                (it as StructureShape).toBuilder().addTrait(AllowInvalidXmlRoot()).build()
            }
        }.let(StripBucketFromHttpPath()::transform)

    private fun isInInvalidXmlRootAllowList(shape: Shape): Boolean {
        return shape.isStructureShape && invalidXmlRootAllowList.contains(shape.id)
    }
}

class S3ProtocolOverride(codegenContext: CodegenContext) : RestXml(codegenContext) {
    private val runtimeConfig = codegenContext.runtimeConfig
    private val errorScope = arrayOf(
        "Bytes" to RuntimeType.Bytes,
        "Error" to RuntimeType.genericError(runtimeConfig),
        "ErrorBuilder" to RuntimeType.genericErrorBuilder(runtimeConfig),
        "HeaderMap" to RuntimeType.HttpHeaderMap,
        "Response" to RuntimeType.HttpResponse,
        "XmlDecodeError" to RuntimeType.smithyXml(runtimeConfig).resolve("decode::XmlDecodeError"),
        "base_errors" to restXmlErrors,
    )

    override fun parseHttpGenericError(operationShape: OperationShape): RuntimeType {
        return RuntimeType.forInlineFun("parse_http_generic_error", RustModule.private("xml_deser")) {
            rustBlockTemplate(
                "pub fn parse_http_generic_error(response: &#{Response}<#{Bytes}>) -> Result<#{ErrorBuilder}, #{XmlDecodeError}>",
                *errorScope,
            ) {
                rustTemplate(
                    """
                    // S3 HEAD responses have no response body to for an error code. Therefore,
                    // check the HTTP response status and populate an error code for 404s.
                    if response.body().is_empty() {
                        let mut builder = #{Error}::builder();
                        if response.status().as_u16() == 404 {
                            builder = builder.code("NotFound");
                        }
                        Ok(builder)
                    } else {
                        #{base_errors}::parse_generic_error(response.body().as_ref())
                    }
                    """,
                    *errorScope,
                )
            }
        }
    }
}
