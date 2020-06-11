/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.go.codegen.integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.CodegenUtils;
import software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.HttpTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait.Format;
import software.amazon.smithy.utils.OptionalUtils;


/**
 * Abstract implementation useful for all protocols that use HTTP bindings.
 */
public abstract class HttpBindingProtocolGenerator implements ProtocolGenerator {

    private static final Logger LOGGER = Logger.getLogger(HttpBindingProtocolGenerator.class.getName());

    private final boolean isErrorCodeInBody;
    private final Set<Shape> serializeDocumentBindingShapes = new TreeSet<>();
    private final Set<Shape> deserializeDocumentBindingShapes = new TreeSet<>();
    private final Set<ShapeId> serializeErrorBindingShapes = new TreeSet<>();

    /**
     * Creates a Http binding protocol generator.
     *
     * @param isErrorCodeInBody A boolean that indicates if the error code for the implementing protocol is located in
     *                          the error response body, meaning this generator will parse the body before attempting to
     *                          load an error code.
     */
    public HttpBindingProtocolGenerator(boolean isErrorCodeInBody) {
        this.isErrorCodeInBody = isErrorCodeInBody;
    }

    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return ApplicationProtocol.createDefaultHttpApplicationProtocol();
    }

    @Override
    public void generateSharedSerializerComponents(GenerationContext context) {
        serializeDocumentBindingShapes.addAll(resolveRequiredDocumentShapeSerializers(context.getModel(),
                serializeDocumentBindingShapes));
        generateDocumentBodyShapeSerializers(context, serializeDocumentBindingShapes);
    }

    /**
     * Resolves the entire set of shapes that will require serializers, deserializers given an initial set of shapes.
     *
     * @param model  the model
     * @param shapes the shapes to walk and resolve additional required serializers, deserializers for
     * @return the complete set of shapes requiring serializers, deserializers
     */
    private Set<Shape> resolveRequiredDocumentShapeSerializers(Model model, Set<Shape> shapes) {
        Set<ShapeId> processed = new TreeSet<>();
        Set<Shape> resolvedShapes = new TreeSet<>();
        Walker walker = new Walker(model);

        shapes.forEach(shape -> {
            processed.add(shape.getId());
            resolvedShapes.add(shape);
            walker.iterateShapes(shape,
                    relationship -> {
                        switch (relationship.getRelationshipType()) {
                            case STRUCTURE_MEMBER:
                            case UNION_MEMBER:
                            case LIST_MEMBER:
                            case SET_MEMBER:
                            case MAP_VALUE:
                            case MEMBER_TARGET:
                                return true;
                            default:
                                return false;
                        }
                    })
                    .forEachRemaining(walkedShape -> {
                        // MemberShape type itself is not what we are interested in
                        if (walkedShape.getType() == ShapeType.MEMBER) {
                            return;
                        }
                        if (processed.contains(walkedShape.getId())) {
                            return;
                        }
                        if (isShapeTypeDocumentSerializerRequired(walkedShape.getType())) {
                            resolvedShapes.add(walkedShape);
                            processed.add(walkedShape.getId());
                        }
                    });
        });

        return resolvedShapes;
    }

    /**
     * Returns whether a document serializer, deserializer function is required to serializer the given shape type.
     *
     * @param shapeType the shape type
     * @return whether the shape type requires a document serializer, deserializer function
     */
    protected boolean isShapeTypeDocumentSerializerRequired(ShapeType shapeType) {
        switch (shapeType) {
            case MAP:
            case LIST:
            case SET:
            case DOCUMENT:
            case STRUCTURE:
            case UNION:
                return true;
            default:
                return false;
        }
    }

    /**
     * Get the operations with HTTP Bindings.
     *
     * @param context the generation context
     * @return the list of operation shapes
     */
    public List<OperationShape> getHttpBindingOperations(GenerationContext context) {
        TopDownIndex topDownIndex = context.getModel().getKnowledge(TopDownIndex.class);

        List<OperationShape> containedOperations = new ArrayList<>();
        for (OperationShape operation : topDownIndex.getContainedOperations(context.getService())) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> containedOperations.add(operation),
                    () -> LOGGER.warning(String.format(
                            "Unable to fetch %s protocol request bindings for %s because it does not have an "
                                    + "http binding trait", getProtocol(), operation.getId()))
            );
        }
        return containedOperations;
    }

    @Override
    public void generateRequestSerializers(GenerationContext context) {
        for (OperationShape operation : getHttpBindingOperations(context)) {
            generateOperationSerializer(context, operation);
        }
    }

    /**
     * Gets the default serde format for timestamps.
     *
     * @return Returns the default format.
     */
    protected abstract Format getDocumentTimestampFormat();

    /**
     * Gets the default content-type when a document is synthesized in the body.
     *
     * @return Returns the default content-type.
     */
    protected abstract String getDocumentContentType();

    private void generateOperationSerializer(
            GenerationContext context,
            OperationShape operation
    ) {
        generateOperationSerializerMiddleware(context, operation);
        generateOperationHttpBindingSerializer(context, operation);
        generateOperationDocumentSerializer(context, operation);
        addOperationDocumentShapeBindersForSerializer(context, operation);
    }

    /**
     * Generates the operation document serializer function.
     *
     * @param context   the generation context
     * @param operation the operation shape being generated
     */
    protected abstract void generateOperationDocumentSerializer(GenerationContext context, OperationShape operation);

    /**
     * Adds the top-level shapes from the operation that bind to the body document that require serializer functions.
     *
     * @param context   the generator context
     * @param operation the operation to add document binders from
     */
    private void addOperationDocumentShapeBindersForSerializer(GenerationContext context, OperationShape operation) {
        Model model = context.getModel();

        // Walk and add members shapes to the list that will require serializer functions
        Collection<HttpBinding> bindings = model.getKnowledge(HttpBindingIndex.class)
                .getRequestBindings(operation).values();

        for (HttpBinding binding : bindings) {
            Shape targetShape = model.expectShape(binding.getMember().getTarget());
            // Check if the input shape has a members that target the document or payload and require serializers
            if (isShapeTypeDocumentSerializerRequired(targetShape.getType())
                    && (binding.getLocation() == HttpBinding.Location.DOCUMENT
                    || binding.getLocation() == HttpBinding.Location.PAYLOAD)) {
                serializeDocumentBindingShapes.add(targetShape);
            }
        }
    }

    private void generateOperationSerializerMiddleware(GenerationContext context, OperationShape operation) {
        GoStackStepMiddlewareGenerator middleware = GoStackStepMiddlewareGenerator.createSerializeStepMiddleware(
                ProtocolGenerator.getSerializeMiddlewareName(operation.getId(), getProtocolName()
                ));

        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        Shape inputShape = model.expectShape(operation.getInput()
                .orElseThrow(() -> new CodegenException("expect input shape for operation: " + operation.getId())));
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        ApplicationProtocol applicationProtocol = getApplicationProtocol();
        Symbol requestType = applicationProtocol.getRequestType();
        HttpTrait httpTrait = operation.expectTrait(HttpTrait.class);

        middleware.writeMiddleware(context.getWriter(), (generator, writer) -> {
            writer.addUseImports(SmithyGoDependency.FMT);
            writer.addUseImports(SmithyGoDependency.SMITHY);
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_BINDING);

            // cast input request to smithy transport type, check for failures
            writer.write("request, ok := in.Request.($P)", requestType);
            writer.openBlock("if !ok {", "}", () -> {
                writer.write("return out, metadata, "
                        + "&smithy.SerializationError{Err: fmt.Errorf(\"unknown transport type %T\", in.Request)}");
            });
            writer.write("");

            // cast input parameters type to the input type of the operation
            writer.write("input, ok := in.Parameters.($P)", inputSymbol);
            writer.write("_ = input");
            writer.openBlock("if !ok {", "}", () -> {
                writer.write("return out, metadata, "
                        + "&smithy.SerializationError{Err: fmt.Errorf(\"unknown input parameters type %T\","
                        + " in.Parameters)}");
            });

            writer.write("");
            writer.write("request.Request.URL.Path = $S", httpTrait.getUri());
            writer.write("request.Request.Method = $S", httpTrait.getMethod());
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_BINDING);
            writer.write("restEncoder := httpbinding.NewEncoder(request.Request)");
            writer.write("");

            // we only generate an operations http bindings function if there are bindings
            if (isOperationWithRestRequestBindings(model, operation)) {
                String serFunctionName = ProtocolGenerator.getOperationHttpBindingsSerFunctionName(inputShape,
                        getProtocolName());
                writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_BINDING);
                writer.openBlock("if err := $L(input, restEncoder); err != nil {", "}", serFunctionName, () -> {
                    writer.write("return out, metadata, &smithy.SerializationError{Err: err}");
                });
                writer.write("");
            }

            // delegate the setup and usage of the document serializer function for the protocol
            writeMiddlewareDocumentSerializerDelegator(model, symbolProvider, operation, generator, writer);
            writer.write("");
            writer.openBlock("if err := restEncoder.Encode(); err != nil {", "}", () -> {
                writer.write("return out, metadata, &smithy.SerializationError{Err: err}");
            });
            writer.write("");
            writer.write("return next.$L(ctx, in)", generator.getHandleMethodName());
        });
    }

    // Generates operation deserializer middleware that delegates to appropriate deserializers for the error,
    // output shapes for the operation.
    private void generateOperationDeserializerMiddleware(GenerationContext context, OperationShape operation) {
        GoStackStepMiddlewareGenerator middleware = GoStackStepMiddlewareGenerator.createDeserializeStepMiddleware(
                ProtocolGenerator.getDeserializeMiddlewareName(operation.getId(), getProtocolName()));

        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();

        ApplicationProtocol applicationProtocol = getApplicationProtocol();
        Symbol responseType = applicationProtocol.getResponseType();
        GoWriter goWriter = context.getWriter();

        middleware.writeMiddleware(goWriter, (generator, writer) -> {
            writer.addUseImports(SmithyGoDependency.FMT);

            writer.write("out, metadata, err = next.$L(ctx, in)", generator.getHandleMethodName());
            writer.write("if err != nil { return out, metadata, err }");
            writer.write("");

            writer.write("response, ok := out.RawResponse.($P)", responseType);
            writer.openBlock("if !ok {", "}", () -> {
                writer.addUseImports(SmithyGoDependency.SMITHY);
                writer.write(String.format("return out, metadata, &smithy.DeserializationError{Err: %s}",
                        "fmt.Errorf(\"unknown transport type %T\", out.RawResponse)"));
            });
            writer.write("");

            // Error shape middleware generation
            writeMiddlewareErrorDeserializer(writer, model, symbolProvider, operation, generator);

            Shape outputShape = model.expectShape(operation.getOutput()
                    .orElseThrow(() -> new CodegenException("expect output shape for operation: " + operation.getId()))
            );

            Symbol outputSymbol = symbolProvider.toSymbol(outputShape);

            // initialize out.Result as output structure shape
            writer.write("output := &$T{}", outputSymbol);
            writer.write("out.Result = output");
            writer.write("");

            // Output shape HTTP binding middleware generation
            if (isShapeWithRestResponseBindings(model, operation)) {
                String deserFuncName = ProtocolGenerator.getOperationHttpBindingsDeserFunctionName(
                        outputShape, getProtocolName());

                writer.write("err= $L(output, response)", deserFuncName);
                writer.openBlock("if err != nil {", "}", () -> {
                    writer.addUseImports(SmithyGoDependency.SMITHY);
                    writer.write(String.format("return out, metadata, &smithy.DeserializationError{Err: %s}",
                            "fmt.Errorf(\"failed to decode response with invalid Http bindings, %w\", err)"));
                });
                writer.write("");
            }

            // Output Shape Document Binding middleware generation
            if (isShapeWithResponseBindings(model, operation, HttpBinding.Location.DOCUMENT)
                    || isShapeWithResponseBindings(model, operation, HttpBinding.Location.PAYLOAD)) {
                writeMiddlewareDocumentDeserializerDelegator(writer, model, symbolProvider, operation, generator);
                writer.write("");
            }

            writer.write("return out, metadata, err");
        });
        goWriter.write("");
    }

    /**
     * Generate the document serializer logic for the serializer middleware body.
     *
     * @param model          the model
     * @param symbolProvider the symbol provider
     * @param operation      the operation
     * @param generator      middleware generator definition
     * @param writer         the writer within the middlware context
     */
    protected abstract void writeMiddlewareDocumentSerializerDelegator(
            Model model,
            SymbolProvider symbolProvider,
            OperationShape operation,
            GoStackStepMiddlewareGenerator generator,
            GoWriter writer
    );

    /**
     * Generate the document deserializer logic for the deserializer middleware body.
     *
     * @param writer         the writer within the middleware context
     * @param model          the model
     * @param symbolProvider the symbol provider
     * @param operation      the operation
     * @param generator      middleware generator definition
     */
    protected abstract void writeMiddlewareDocumentDeserializerDelegator(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            OperationShape operation,
            GoStackStepMiddlewareGenerator generator
    );


    /**
     * Generate the document deserializer logic for the deserializer middleware body.
     *
     * @param model          the model
     * @param symbolProvider the symbol provider
     * @param operation      the operation
     * @param generator      middleware generator definition
     * @param writer         the writer within the middleware context
     */
    protected void writeMiddlewareErrorDeserializer(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            OperationShape operation,
            GoStackStepMiddlewareGenerator generator
    ) {
    }

    private boolean isRestBinding(HttpBinding.Location location) {
        return location == HttpBinding.Location.HEADER
                || location == HttpBinding.Location.PREFIX_HEADERS
                || location == HttpBinding.Location.LABEL
                || location == HttpBinding.Location.QUERY;
    }

    // returns whether an operation shape has Rest Request Bindings
    private boolean isOperationWithRestRequestBindings(Model model, OperationShape operationShape) {
        Collection<HttpBinding> bindings = model.getKnowledge(HttpBindingIndex.class)
                .getRequestBindings(operationShape).values();

        for (HttpBinding binding : bindings) {
            if (isRestBinding(binding.getLocation())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns whether a shape has rest response bindings.
     * The shape can be an operation shape, error shape or an output shape.
     *
     * @param model the model
     * @param shape the shape with possible presence of rest response bindings
     * @return boolean indicating presence of rest response bindings in the shape
     */
    protected boolean isShapeWithRestResponseBindings(Model model, Shape shape) {
        Collection<HttpBinding> bindings = model.getKnowledge(HttpBindingIndex.class)
                .getResponseBindings(shape).values();

        for (HttpBinding binding : bindings) {
            if (isRestBinding(binding.getLocation())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether a shape has response bindings for the provided HttpBinding location.
     * The shape can be an operation shape, error shape or an output shape.
     *
     * @param model    the model
     * @param shape    the shape with possible presence of response bindings
     * @param location the HttpBinding location for response binding
     * @return boolean indicating presence of response bindings in the shape for provided location
     */
    protected boolean isShapeWithResponseBindings(Model model, Shape shape, HttpBinding.Location location) {
        Collection<HttpBinding> bindings = model.getKnowledge(HttpBindingIndex.class)
                .getResponseBindings(shape).values();

        for (HttpBinding binding : bindings) {
            if (binding.getLocation() == location) {
                return true;
            }
        }
        return false;
    }

    private void generateOperationHttpBindingSerializer(
            GenerationContext context,
            OperationShape operation
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        GoWriter writer = context.getWriter();

        Shape inputShape = model.expectShape(operation.getInput()
                .orElseThrow(() -> new CodegenException("missing input shape for operation: " + operation.getId())));

        HttpBindingIndex bindingIndex = model.getKnowledge(HttpBindingIndex.class);
        Map<String, HttpBinding> bindingMap = bindingIndex.getRequestBindings(operation).entrySet().stream()
                .filter(entry -> isRestBinding(entry.getValue().getLocation()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> {
                    throw new CodegenException("found duplicate binding entries for same response operation shape");
                }, TreeMap::new));

        Symbol restEncoder = getRestEncoderSymbol();
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        String functionName = ProtocolGenerator.getOperationHttpBindingsSerFunctionName(inputShape, getProtocolName());

        writer.addUseImports(SmithyGoDependency.FMT);
        writer.openBlock("func $L(v $P, encoder $P) error {", "}", functionName, inputSymbol, restEncoder,
                () -> {
                    writer.openBlock("if v == nil {", "}", () -> {
                        writer.write("return fmt.Errorf(\"unsupported serialization of nil %T\", v)");
                    });

                    writer.write("");

                    for (Map.Entry<String, HttpBinding> entry : bindingMap.entrySet()) {
                        HttpBinding binding = entry.getValue();
                        writeHttpBindingMember(writer, model, symbolProvider, binding);
                        writer.write("");
                    }
                    writer.write("return nil");
                });
        writer.write("");
    }

    private Symbol getRestEncoderSymbol() {
        return SymbolUtils.createPointableSymbolBuilder("Encoder", SmithyGoDependency.SMITHY_HTTP_BINDING).build();
    }

    private void generateHttpBindingTimestampSerializer(
            Model model,
            GoWriter writer,
            MemberShape memberShape,
            HttpBinding.Location location,
            String operand,
            BiConsumer<GoWriter, String> locationEncoder
    ) {
        writer.addUseImports(SmithyGoDependency.SMITHY_TIME);

        TimestampFormatTrait.Format format = model.getKnowledge(HttpBindingIndex.class).determineTimestampFormat(
                memberShape, location, getDocumentTimestampFormat());

        switch (format) {
            case DATE_TIME:
                locationEncoder.accept(writer, "String(smithytime.FormatDateTime(" + operand + "))");
                break;
            case HTTP_DATE:
                locationEncoder.accept(writer, "String(smithytime.FormatHTTPDate(" + operand + "))");
                break;
            case EPOCH_SECONDS:
                locationEncoder.accept(writer, "Double(smithytime.FormatEpochSeconds(" + operand + "))");
                break;
            default:
                throw new CodegenException("Unknown timestamp format");
        }
    }

    private void writeHttpBindingSetter(
            Model model,
            GoWriter writer,
            MemberShape memberShape,
            HttpBinding.Location location,
            String operand,
            BiConsumer<GoWriter, String> locationEncoder
    ) {
        Shape targetShape = model.expectShape(memberShape.getTarget());

        // We only need to dereference if we pass the shape around as reference in Go.
        // Note we make two exceptions here: big.Int and big.Float should still be passed as reference to the helper
        // method as they can be arbitrarily large.
        operand = CodegenUtils.isShapePassByReference(targetShape)
                && targetShape.getType() != ShapeType.BIG_INTEGER
                && targetShape.getType() != ShapeType.BIG_DECIMAL
                ? "*" + operand : operand;

        switch (targetShape.getType()) {
            case BOOLEAN:
                locationEncoder.accept(writer, "Boolean(" + operand + ")");
                break;
            case STRING:
                operand = targetShape.hasTrait(EnumTrait.class) ? "string(" + operand + ")" : operand;
                locationEncoder.accept(writer, "String(" + operand + ")");
                break;
            case TIMESTAMP:
                generateHttpBindingTimestampSerializer(model, writer, memberShape, location, operand, locationEncoder);
                break;
            case BYTE:
                locationEncoder.accept(writer, "Byte(" + operand + ")");
                break;
            case SHORT:
                locationEncoder.accept(writer, "Short(" + operand + ")");
                break;
            case INTEGER:
                locationEncoder.accept(writer, "Integer(" + operand + ")");
                break;
            case LONG:
                locationEncoder.accept(writer, "Long(" + operand + ")");
                break;
            case FLOAT:
                locationEncoder.accept(writer, "Float(" + operand + ")");
                break;
            case DOUBLE:
                locationEncoder.accept(writer, "Double(" + operand + ")");
                break;
            case BIG_INTEGER:
                locationEncoder.accept(writer, "BigInteger(" + operand + ")");
                break;
            case BIG_DECIMAL:
                locationEncoder.accept(writer, "BigDecimal(" + operand + ")");
                break;
            default:
                throw new CodegenException("unexpected shape type " + targetShape.getType());
        }
    }

    private void writeHttpBindingMember(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            HttpBinding binding
    ) {
        MemberShape memberShape = binding.getMember();
        Shape targetShape = model.expectShape(memberShape.getTarget());
        String memberName = symbolProvider.toMemberName(memberShape);

        writeSafeOperandAccessor(model, symbolProvider, memberShape, "v", writer, (bodyWriter, operand) -> {
            HttpBinding.Location location = binding.getLocation();
            switch (location) {
                case HEADER:
                    if (targetShape instanceof CollectionShape) {
                        MemberShape collectionMemberShape = ((CollectionShape) targetShape).getMember();
                        bodyWriter.openBlock("for i := range $L {", "}", operand, () -> {
                            writeHttpBindingSetter(model, writer, collectionMemberShape, location, operand + "[i]",
                                    (w, s) -> {
                                        w.writeInline("encoder.AddHeader($S).$L", memberShape.getMemberName(), s);
                                    });
                        });
                    } else {
                        writeHttpBindingSetter(model, writer, memberShape, location, operand, (w, s) -> w.writeInline(
                                "encoder.SetHeader($S).$L", memberShape.getMemberName(), s));
                    }
                    break;
                case PREFIX_HEADERS:
                    MemberShape valueMemberShape = targetShape.asMapShape()
                            .orElseThrow(() -> new CodegenException("prefix headers must target map shape"))
                            .getValue();
                    Shape valueMemberTarget = model.expectShape(valueMemberShape.getTarget());

                    bodyWriter.write("hv := encoder.Headers($S)", memberName);
                    bodyWriter.openBlock("for i := range $L {", "}", operand, () -> {
                        if (valueMemberTarget instanceof CollectionShape) {
                            MemberShape collectionMemberShape = ((CollectionShape) valueMemberTarget).getMember();
                            bodyWriter.openBlock("for j := range $L[i] {", "}", operand, () -> {
                                writeHttpBindingSetter(model, writer, collectionMemberShape, location,
                                        operand + "[i][j]", (w, s) -> w.writeInline("hv.AddHeader($S).$L",
                                                memberShape.getMemberName(), s));
                            });
                        } else {
                            writeHttpBindingSetter(model, writer, valueMemberShape, location, operand + "[i]",
                                    (w, s) -> w.writeInline("hv.AddHeader($S).$L", memberShape.getMemberName(), s));
                        }
                    });
                    break;
                case LABEL:
                    writeHttpBindingSetter(model, writer, memberShape, location, operand, (w, s) -> {
                        w.writeInline("if err := encoder.SetURI($S).$L", memberShape.getMemberName(), s);
                        w.write("; err != nil {\n"
                                + "\treturn err\n"
                                + "}");
                    });
                    break;
                case QUERY:
                    if (targetShape instanceof CollectionShape) {
                        MemberShape collectionMember = ((CollectionShape) targetShape).getMember();
                        bodyWriter.openBlock("for i := range $L {", "}", operand, () -> {
                            writeHttpBindingSetter(model, writer, collectionMember, location, operand + "[i]",
                                    (w, s) -> w.writeInline("encoder.AddQuery($S).$L", memberShape.getMemberName(), s));
                        });
                    } else {
                        writeHttpBindingSetter(model, writer, memberShape, location, operand, (w, s) -> w.writeInline(
                                "encoder.SetQuery($S).$L", memberShape.getMemberName(), s));
                    }
                    break;
                default:
                    throw new CodegenException("unexpected http binding found");
            }
        });
    }

    /**
     * Writes a conditional check of the provided operand represented by the member shape.
     * This check is to verify if the provided Go value was set by the user and whether the value
     * should be serialized to the transport request.
     *
     * @param model          the model being generated
     * @param symbolProvider the symbol provider
     * @param memberShape    the member shape being accessed
     * @param operand        the Go operand representing the member shape
     * @param writer         the writer
     * @param consumer       a consumer that will be given the writer to populate the accessor body
     */
    protected void writeSafeOperandAccessor(
            Model model,
            SymbolProvider symbolProvider,
            MemberShape memberShape,
            String operand,
            GoWriter writer,
            BiConsumer<GoWriter, String> consumer
    ) {
        Shape targetShape = model.expectShape(memberShape.getTarget());

        String memberName = symbolProvider.toMemberName(memberShape);

        boolean enumShape = targetShape.hasTrait(EnumTrait.class);

        operand = operand + "." + memberName;

        if (!enumShape && !CodegenUtils.isNilAssignableToShape(model, memberShape)) {
            consumer.accept(writer, operand);
            return;
        }

        String conditionCheck;
        if (enumShape) {
            conditionCheck = "len(" + operand + ") > 0";
        } else {
            conditionCheck = operand + " != nil";
        }

        String resolvedOperand = operand;
        writer.openBlock("if " + conditionCheck + " {", "}", () -> {
            consumer.accept(writer, resolvedOperand);
        });
    }

    /**
     * Generates serialization functions for shapes in the passed set. These functions
     * should return a value that can then be serialized by the implementation of
     * {@code serializeInputDocument}.
     *
     * @param context The generation context.
     * @param shapes  The shapes to generate serialization for.
     */
    protected abstract void generateDocumentBodyShapeSerializers(GenerationContext context, Set<Shape> shapes);

    @Override
    public void generateResponseDeserializers(GenerationContext context) {
        for (OperationShape operation : getHttpBindingOperations(context)) {
            generateOperationDeserializerMiddleware(context, operation);
            generateOperationHttpBindingDeserializer(context, operation);
            generateOperationDocumentDeserializer(context, operation);
            addOperationDocumentShapeBindersForDeserializer(context, operation);
            addErrorShapeBinders(context, operation);
        }

        for (ShapeId errorBinding : serializeErrorBindingShapes) {
            generateErrorHttpBindingDeserializer(context, errorBinding);
            generateErrorDocumentBindingDeserializer(context, errorBinding);
        }
    }

    // Generates Http Binding deserializer for operation output shape
    private void generateOperationHttpBindingDeserializer(
            GenerationContext context,
            OperationShape operation
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        GoWriter writer = context.getWriter();

        Shape outputShape = model.expectShape(operation.getOutput()
                .orElseThrow(() -> new CodegenException(
                        "missing output shape for operation: " + operation.getId())));

        HttpBindingIndex bindingIndex = model.getKnowledge(HttpBindingIndex.class);
        Map<String, HttpBinding> bindingMap = bindingIndex.getResponseBindings(operation).entrySet().stream()
                .filter(entry -> isRestBinding(entry.getValue().getLocation()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> {
                    throw new CodegenException("found duplicate binding entries for same response operation shape");
                }, TreeMap::new));

        // do not generate if no HTTPBinding for operation output
        if (bindingMap.size() == 0) {
            return;
        }

        generateHttpBindingShapeDeserializerFunction(writer, model, symbolProvider, outputShape, bindingMap);
    }

    // Generate Http Binding deserializer for operation Error shape
    private void generateErrorHttpBindingDeserializer(
            GenerationContext context,
            ShapeId errorBinding
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        GoWriter writer = context.getWriter();
        HttpBindingIndex bindingIndex = model.getKnowledge(HttpBindingIndex.class);
        Shape errorBindingShape = model.expectShape(errorBinding);

        Map<String, HttpBinding> bindingMap = bindingIndex.getResponseBindings(errorBinding).entrySet().stream()
                .filter(entry -> isRestBinding(entry.getValue().getLocation()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> {
                    throw new CodegenException("found duplicate binding entries for same error shape");
                }, TreeMap::new));

        // do not generate if no HTTPBinding for Error Binding
        if (bindingMap.size() == 0) {
            return;
        }

        generateHttpBindingShapeDeserializerFunction(writer, model, symbolProvider, errorBindingShape, bindingMap);
    }

    // Generates Http Binding shape deserializer function.
    private void generateHttpBindingShapeDeserializerFunction(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            Shape targetShape,
            Map<String, HttpBinding> bindingMap
    ) {
        Symbol targetSymbol = symbolProvider.toSymbol(targetShape);
        Symbol smithyHttpResponsePointableSymbol = SymbolUtils.createPointableSymbolBuilder(
                "Response", SmithyGoDependency.SMITHY_HTTP_TRANSPORT).build();

        writer.addUseImports(SmithyGoDependency.FMT);

        String functionName = ProtocolGenerator.getOperationHttpBindingsDeserFunctionName(targetShape,
                getProtocolName());
        writer.openBlock("func $L(v $P, response $P) error {", "}",
                functionName, targetSymbol, smithyHttpResponsePointableSymbol,
                () -> {
                    writer.openBlock("if v == nil {", "}", () -> {
                        writer.write("return fmt.Errorf(\"unsupported deserialization for nil %T\", v)");
                    });
                    writer.write("");

                    for (Map.Entry<String, HttpBinding> entry : bindingMap.entrySet()) {
                        HttpBinding binding = entry.getValue();
                        writeRestDeserializerMember(writer, model, symbolProvider, binding);
                        writer.write("");
                    }
                    writer.write("return nil");
                });
    }


    private void addErrorShapeBinders(GenerationContext context, OperationShape operation) {
        for (ShapeId errorBinding : operation.getErrors()) {
            serializeErrorBindingShapes.add(errorBinding);
        }
    }

    private String generateHttpBindingsValue(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            Shape targetShape,
            HttpBinding binding,
            String operand
    ) {
        String value = "";
        switch (targetShape.getType()) {
            case STRING:
                if (targetShape.hasTrait(EnumTrait.class)) {
                    value = String.format("types.%s(%s)", targetShape.getId().getName(), operand);
                    return value;
                }
                return operand;
            case BOOLEAN:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseBool($L)", operand);
                writer.write("if err != nil { return err }");
                return "vv";
            case TIMESTAMP:
                writer.addUseImports(SmithyGoDependency.SMITHY_TIME);
                HttpBindingIndex bindingIndex = model.getKnowledge(HttpBindingIndex.class);
                TimestampFormatTrait.Format format = bindingIndex.determineTimestampFormat(
                        targetShape,
                        binding.getLocation(),
                        Format.HTTP_DATE
                );
                switch (format) {
                    case EPOCH_SECONDS:
                        writer.addUseImports(SmithyGoDependency.STRCONV);
                        writer.write("f, err := strconv.ParseFloat($L, 64)", operand);
                        writer.write("if err != nil { return err }");
                        writer.write("t := smithytime.ParseEpochSeconds(f)");
                        break;
                    case HTTP_DATE:
                        writer.write("t, err := smithytime.ParseHTTPDate($L)", operand);
                        writer.write("if err != nil { return err }");
                        break;
                    case DATE_TIME:
                        writer.write("t, err := smithytime.ParseDateTime($L)", operand);
                        writer.write("if err != nil { return err }");
                        break;
                    default:
                        throw new CodegenException("Unexpected timestamp format " + format);
                }
                return "t";
            case BYTE:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseInt($L, 0, 8)", operand);
                writer.write("if err != nil { return err }");
                return "int8(vv)";
            case SHORT:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseInt($L, 0, 16)", operand);
                writer.write("if err != nil { return err }");
                return "int16(vv)";
            case INTEGER:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseInt($L, 0, 32)", operand);
                writer.write("if err != nil { return err }");
                return "int32(vv)";
            case LONG:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseInt($L, 0, 64)", operand);
                writer.write("if err != nil { return err }");
                return "vv";
            case FLOAT:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseFloat($L, 32)", operand);
                writer.write("if err != nil { return err }");
                return "float32(vv)";
            case DOUBLE:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("vv, err := strconv.ParseFloat($L, 64)", operand);
                writer.write("if err != nil { return err }");
                return "vv";
            case BIG_INTEGER:
                writer.addUseImports(SmithyGoDependency.BIG);
                writer.write("i := big.NewInt(0)");
                writer.write("bi, ok := i.SetString($L,0)", operand);
                writer.openBlock("if !ok {", "}", () -> {
                    writer.write(
                            "return fmt.Error($S)",
                            "Incorrect conversion from string to BigInteger type"
                    );
                });
                return "*bi";
            case BIG_DECIMAL:
                writer.addUseImports(SmithyGoDependency.BIG);
                writer.write("f := big.NewFloat(0)");
                writer.write("bd, ok := f.SetString($L,0)", operand);
                writer.openBlock("if !ok {", "}", () -> {
                    writer.write(
                            "return fmt.Error($S)",
                            "Incorrect conversion from string to BigDecimal type"
                    );
                });
                return "*bd";
            case BLOB:
                writer.addUseImports(SmithyGoDependency.BASE64);
                writer.write("b, err := base64.StdEncoding.DecodeString($L)", operand);
                writer.write("if err != nil { return err }");
                return "b";
            case SET:
                // handle set as target shape
                Shape targetValueSetShape = model.expectShape(targetShape.asSetShape().get().getMember().getTarget());
                return getCollectionDeserializer(writer, model, symbolProvider, targetValueSetShape, binding, operand);
            case LIST:
                // handle list as target shape
                Shape targetValueListShape = model.expectShape(targetShape.asListShape().get().getMember().getTarget());
                return getCollectionDeserializer(writer, model, symbolProvider, targetValueListShape, binding, operand);
            default:
                throw new CodegenException("unexpected shape type " + targetShape.getType());
        }
    }

    private String getCollectionDeserializer(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            Shape targetShape,
            HttpBinding binding,
            String operand
    ) {
        Symbol targetSymbol = symbolProvider.toSymbol(targetShape);
        writer.write("list := make([]$P, 0, 0)", targetSymbol);

        writer.addUseImports(SmithyGoDependency.STRINGS);
        writer.openBlock("for _, i := range strings.Split($L[1:len($L)-1], $S) {",
                "}", operand, operand, ",",
                () -> {
                    String value = generateHttpBindingsValue(writer, model, symbolProvider, targetShape, binding, "i");
                    writer.write("list = append(list, $L)",
                            CodegenUtils.generatePointerValueIfPointable(writer, targetShape, value));
                });
        return "list";
    }

    private void writeRestDeserializerMember(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            HttpBinding binding
    ) {
        MemberShape memberShape = binding.getMember();
        Shape targetShape = model.expectShape(memberShape.getTarget());
        String memberName = symbolProvider.toMemberName(memberShape);

        switch (binding.getLocation()) {
            case HEADER:
                writeHeaderDeserializerFunction(writer, model, symbolProvider, memberName, targetShape, binding);
                break;
            case PREFIX_HEADERS:
                if (!targetShape.isMapShape()) {
                    throw new CodegenException("unexpected prefix-header shape type found in Http bindings");
                }
                writePrefixHeaderDeserializerFunction(writer, model, symbolProvider, memberName, targetShape, binding);
                break;
            default:
                throw new CodegenException("unexpected http binding found");
        }
    }

    private void writeHeaderDeserializerFunction(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            String memberName,
            Shape targetShape,
            HttpBinding binding
    ) {
        writer.openBlock("if val := response.Header.Get($S); val != $S {", "}",
                binding.getLocationName(), "", () -> {
                    String value = generateHttpBindingsValue(writer, model, symbolProvider,
                            targetShape, binding, "val");
                    writer.write("v.$L = $L", memberName,
                            CodegenUtils.generatePointerValueIfPointable(writer, targetShape, value));
                });
    }

    private void writePrefixHeaderDeserializerFunction(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            String memberName,
            Shape targetShape,
            HttpBinding binding
    ) {
        String prefix = binding.getLocationName();
        Shape targetValueShape = model.expectShape(targetShape.asMapShape().get().getValue().getTarget());
        for (Shape shape : targetShape.asMapShape().get().members()) {
            String name = shape.getId().getName();
            String locationName = prefix + name;
            writer.openBlock("if val := response.Header.Get($S); val != $S {",
                    "}", locationName, "", () -> {
                        String value = generateHttpBindingsValue(writer, model, symbolProvider,
                                targetValueShape, binding, "val");
                        writer.write("v.$L[$S] = $L", memberName, name,
                                CodegenUtils.generatePointerValueIfPointable(writer, targetValueShape, value));
                    });
        }
    }

    @Override
    public void generateSharedDeserializerComponents(GenerationContext context) {
        deserializeDocumentBindingShapes.addAll(resolveRequiredDocumentShapeSerializers(context.getModel(),
                deserializeDocumentBindingShapes));
        generateDocumentBodyShapeDeserializers(context, deserializeDocumentBindingShapes);
    }

    /**
     * Adds the top-level shapes from the operation that bind to the body document that require deserializer functions.
     *
     * @param context   the generator context
     * @param operation the operation to add document binders from
     */
    private void addOperationDocumentShapeBindersForDeserializer(GenerationContext context, OperationShape operation) {
        Model model = context.getModel();
        HttpBindingIndex httpBindingIndex = model.getKnowledge(HttpBindingIndex.class);
        // Walk and add members shapes to the list that will require deserializer functions
        httpBindingIndex.getResponseBindings(operation).values()
                .forEach(binding -> {
                    Shape targetShape = model.expectShape(binding.getMember().getTarget());
                    if (isShapeTypeDocumentSerializerRequired(targetShape.getType())
                            && (binding.getLocation() == HttpBinding.Location.DOCUMENT
                            || binding.getLocation() == HttpBinding.Location.PAYLOAD)) {
                        deserializeDocumentBindingShapes.add(targetShape);
                    }
                });

        for (ShapeId errorShapeId : operation.getErrors()) {
            httpBindingIndex.getResponseBindings(errorShapeId).values()
                    .forEach(binding -> {
                        Shape targetShape = model.expectShape(binding.getMember().getTarget());
                        if (isShapeTypeDocumentSerializerRequired(targetShape.getType())
                                && (binding.getLocation() == HttpBinding.Location.DOCUMENT
                                || binding.getLocation() == HttpBinding.Location.PAYLOAD)) {
                            deserializeDocumentBindingShapes.add(targetShape);
                        }
                    });
        }
    }

    /**
     * Generates the operation document deserializer function.
     *
     * @param context   the generation context
     * @param operation the operation shape being generated
     */
    protected abstract void generateOperationDocumentDeserializer(GenerationContext context, OperationShape operation);

    /**
     * Generates deserialization functions for shapes in the passed set. These functions
     * should return a value that can then be deserialized by the implementation of
     * {@code deserializeOutputDocument}.
     *
     * @param context The generation context.
     * @param shapes  The shapes to generate deserialization for.
     */
    protected abstract void generateDocumentBodyShapeDeserializers(GenerationContext context, Set<Shape> shapes);

    /**
     * Generates the error document deserializer function.
     *
     * @param context the generation context
     * @param shapeId the error shape id for which deserializer is being generated
     */
    protected abstract void generateErrorDocumentBindingDeserializer(GenerationContext context, ShapeId shapeId);
}
