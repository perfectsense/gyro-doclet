/*
 * Copyright 2019, Perfect Sense, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gyro.doclet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.base.CaseFormat;
import com.psddev.dari.util.ObjectUtils;
import com.psddev.dari.util.StringUtils;
import com.sun.javadoc.AnnotationDesc;
import com.sun.javadoc.AnnotationValue;
import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.PackageDoc;
import com.sun.javadoc.RootDoc;
import com.sun.javadoc.Tag;
import com.sun.javadoc.Type;
import gyro.core.resource.Output;
import gyro.core.validation.CollectionMax;
import gyro.core.validation.CollectionMin;
import gyro.core.validation.ConflictsWith;
import gyro.core.validation.DependsOn;
import gyro.core.validation.Max;
import gyro.core.validation.Min;
import gyro.core.validation.Range;
import gyro.core.validation.Ranges;
import gyro.core.validation.Regex;
import gyro.core.validation.Regexes;
import gyro.core.validation.Required;
import gyro.core.validation.ValidNumbers;
import gyro.core.validation.ValidStrings;

public class ResourceDocGenerator {

    private static final Pattern LEADING_WHITE_SPACE = Pattern.compile("^\\s?");
    private static final Pattern LEADING_WHITE_SPACES = Pattern.compile("^\\s+");
    private static final Pattern SEE_REF_DOC = Pattern.compile(" See `.*>`_\\.");

    private RootDoc root;
    private ClassDoc doc;
    private String namespace;
    private String name;
    private String groupName;
    private String providerPackage;
    private boolean isSubresource = false;

    public ResourceDocGenerator(RootDoc root, ClassDoc doc, boolean isFinder) {
        this.root = root;
        this.doc = doc;

        PackageDoc packageDoc = doc.containingPackage();
        groupName = getDocGroupName(packageDoc);
        name = getResourceType(doc);

        if (name == null) {
            name = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, doc.name().replace("Resource", ""));
            isSubresource = true;
        }

        if (isFinder) {
            name = name + GyroDoclet.FINDER_SUFFIX;
        }

        providerPackage = packageDoc.name().substring(0, packageDoc.name().lastIndexOf('.'));
        PackageDoc rootPackageDoc = root.packageNamed(providerPackage);

        if (rootPackageDoc != null) {
            for (AnnotationDesc annotationDesc : rootPackageDoc.annotations()) {
                if (annotationDesc.annotationType().name().equals("DocNamespace")) {
                    namespace = (String) annotationDesc.elementValues()[0].value().value();
                }
            }
        }

        if (namespace == null && providerPackage.startsWith("gyro.")) {
            namespace = providerPackage.split("\\.")[1];
        }

        if (doc.superclass() != null && doc.superclass().name().equals("Diffable")) {
            isSubresource = true;
        }
    }

    public String generate() {
        if (isSubresource) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        //System.out.println("Generating documentation for: " + resourceName());

        generateHeader(sb);

        if (Arrays.stream(doc.methods())
            .anyMatch(e -> !StringUtils.isBlank(e.commentText()))) {
            sb.append("Attributes\n");
            sb.append(repeat("-", 10));
            sb.append("\n\n");

            sb.append(".. role:: attribute\n\n");
            sb.append(".. role:: resource-type\n\n");
            sb.append(".. role:: collection-type\n\n");

            sb.append(".. list-table::\n");
            sb.append("    :widths: 30 70\n");
            sb.append("    :header-rows: 1\n\n");
            sb.append("    * - Attribute\n");
            sb.append("      - Description\n\n");

            if (writeAttributes(doc, sb, 0, false)) {
                sb.append("Outputs\n");
                sb.append(repeat("-", 7));
                sb.append("\n\n");

                sb.append(".. list-table::\n");
                sb.append("    :widths: 30 70\n");
                sb.append("    :header-rows: 1\n\n");
                sb.append("    * - Attribute\n");
                sb.append("      - Description\n\n");

                generateOutputs(doc, sb, 0);
            }
        }
        return sb.toString();
    }

    public String getProviderPackage() {
        return providerPackage;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getName() {
        return name;
    }

    public String getGroupName() {
        return groupName;
    }

    public static String trimLeadingSpace(String s) {
        if (s == null) {
            return null;
        }
        return LEADING_WHITE_SPACE.matcher(s).replaceAll("");
    }

    public static String trimLeadingSpaces(String s) {
        if (s == null) {
            return null;
        }
        return LEADING_WHITE_SPACES.matcher(s).replaceAll("");
    }

    public static String trim(String s) {
        StringBuilder sb = new StringBuilder();

        if (s != null) {
            for (String line : s.split("\n")) {
                sb.append(trimLeadingSpace(line));
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public static String repeat(String c, int r) {
        return new String(new char[r]).replace("\0", c);
    }

    public static boolean isResource(ClassDoc classDoc) {
        boolean isResource = false;

        if (Arrays.stream(classDoc.annotations())
            .anyMatch(o -> o.annotationType().qualifiedName().equals(gyro.core.Type.class.getName()))) {
            ClassDoc superClass = classDoc.superclass();
            while (superClass != null) {
                if (superClass.name().equals("Resource")) {
                    isResource = true;
                    break;
                }

                superClass = superClass.superclass();
            }
        }

        return isResource;
    }

    public static boolean isFinder(ClassDoc classDoc) {
        boolean isFinder = false;
        ClassDoc superClass = classDoc.superclass();
        while (superClass != null) {
            if (superClass.name().equals("Finder")) {
                isFinder = true;
                break;
            }

            superClass = superClass.superclass();
        }

        return isFinder;
    }

    private String resourceName() {
        return resourceName(name);
    }

    private String resourceName(String name) {
        return String.format("%s::%s", namespace, name.replace(GyroDoclet.FINDER_SUFFIX, ""));
    }

    private void generateHeader(StringBuilder sb) {
        String resourceName = resourceName();
        sb.append(resourceName);
        sb.append("\n");
        sb.append(repeat("=", resourceName.length()));
        sb.append("\n\n");
        sb.append(trim(doc.commentText()));
        sb.append("\n\n");
    }

    private boolean writeAttributes(ClassDoc classDoc, StringBuilder sb, int indent, boolean includeOutput) {
        return writeAttributes(
            classDoc,
            sb,
            indent,
            includeOutput ? OutputMode.INCLUDE_OUTPUT : OutputMode.EXCLUDE_OUTPUT,
            true);
    }

    private boolean writeAttributes(
        ClassDoc classDoc,
        StringBuilder sb,
        int indent,
        OutputMode outputMode,
        boolean tableFormat) {
        boolean hadOutputs = false;

        // Output superclass attributes.
        if (classDoc.superclass() != null
            && !classDoc.superclass().qualifiedName().equals("gyro.core.resource.Resource")
            && !classDoc.superclass().qualifiedName().equals("gyro.core.resource.Diffable")
            && !classDoc.superclass().qualifiedName().equals("gyro.core.finder.Finder")) {
            hadOutputs = writeAttributes(classDoc.superclass(), sb, indent, outputMode, tableFormat);
        }

        // Read each method that contains a comment.
        for (MethodDoc methodDoc : classDoc.methods()) {
            String commentText = methodDoc.commentText();

            if (commentText != null && commentText.length() > 0) {

                commentText = addValidationAnnotationMessage(methodDoc, commentText);

                String attributeName = methodDoc.name();
                attributeName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, attributeName)
                    .replaceFirst("get-", "");

                String attributeSubresourceClass = "";
                boolean attributeIsOutput = false;
                ResourceType attributeResourceType = null;
                StringBuilder resourceLinkBuilder = new StringBuilder();

                attributeIsOutput = isAnnotationPresent(methodDoc, Output.class);

                for (Tag tag : methodDoc.tags()) {
                    if (tag.name().equals("@subresource")) {
                        attributeSubresourceClass = tag.text();
                        attributeResourceType = ResourceType.SUBRESOURCE;
                    } else if (tag.name().equals("@output")) {
                        attributeIsOutput = true;
                    } else if (tag.name().equals("@resource")) {
                        // TODO: cache
                        ClassDoc resourceDoc = root.classNamed(tag.text());
                        String groupName = getDocGroupName(resourceDoc.containingPackage());
                        String resourceType = getResourceType(resourceDoc);

                        if (resourceType == null) {
                            System.err.println("Not a resource type!: " + tag.text());
                        } else {
                            resourceLinkBuilder.append(":ref:`")
                                .append(resourceName(resourceType))
                                .append("<")
                                .append(String.format(GyroDoclet.RESOURCE_LINK_PATTERN, groupName, resourceType))
                                .append(">`");
                            attributeResourceType = ResourceType.RESOURCE;
                        }
                    }
                }

                if ((outputMode == OutputMode.INCLUDE_OUTPUT)
                    || (outputMode == OutputMode.EXCLUDE_OUTPUT && !attributeIsOutput)
                    || (outputMode == OutputMode.OUTPUT_ONLY && attributeIsOutput)) {
                    writeAttribute(
                        methodDoc,
                        sb,
                        attributeName,
                        attributeResourceType,
                        resourceLinkBuilder,
                        commentText,
                        indent,
                        tableFormat);

                    if (attributeResourceType == ResourceType.SUBRESOURCE) {
                        ClassDoc subresourceDoc = root.classNamed(attributeSubresourceClass);

                        if (subresourceDoc != null) {
                            writeAttributes(subresourceDoc, sb, indent + 8, outputMode, false);
                        }
                    }
                }

                if (attributeIsOutput) {
                    hadOutputs = true;
                }
            }
        }

        return hadOutputs;
    }

    private void writeAttribute(
        MethodDoc methodDoc,
        StringBuilder sb,
        String attributeName,
        ResourceType resourceType,
        StringBuilder link,
        String commentText,
        int indent,
        boolean tableFormat) {
        String resourceTypeName = Optional.ofNullable(resourceType)
            .map(ResourceType::toString)
            .orElse(null);

        String genericTypeName = Optional.of(methodDoc.returnType())
            .filter(e -> e.asParameterizedType() != null)
            .map(Type::simpleTypeName)
            .map(String::toLowerCase)
            .orElse(null);

        if (tableFormat) {
            sb.append(repeat(" ", indent + 4));
            sb.append("* - ");
            writeFieldName(sb, attributeName, genericTypeName, resourceTypeName);
            sb.append(repeat(" ", indent + 6));
            sb.append("- ");

            writeLink(sb, link, resourceTypeName, indent + 8);
        } else {
            sb.append(repeat(" ", indent));

            if (genericTypeName != null || resourceType != null) {
                sb.append(".. rst-class:: label-container\n");
                sb.append(repeat(" ", indent));
            }
            writeFieldName(sb, attributeName, genericTypeName, resourceTypeName);
            sb.append(repeat(" ", indent + 4));

            writeLink(sb, link, resourceTypeName, indent + 4);
        }
        sb.append(firstSentence(commentText));
        String rest = comment(commentText, indent + (tableFormat ? 8 : 4));

        if (rest != null && rest.length() > 0) {
            sb.append(rest);
        }
        sb.append("\n\n");
    }

    private void writeFieldName(
        StringBuilder sb,
        String attributeName,
        String genericTypeName,
        String resourceTypeName) {
        sb.append(String.format(":attribute:`%s`", attributeName));

        if (genericTypeName != null) {
            sb.append(String.format(" :collection-type:`%s`", genericTypeName));
        }

        if (resourceTypeName != null) {
            sb.append(String.format(" :resource-type:`%s`", resourceTypeName));
        }
        sb.append("\n");
    }

    private void writeLink(StringBuilder sb, StringBuilder link, String resourceTypeName, int indent) {
        if (link != null && link.length() > 0) {
            sb.append(String.format(".. rst-class:: .%s-link", resourceTypeName));
            sb.append("\n");
            sb.append(repeat(" ", indent));
            sb.append(link);
            sb.append(" ");
            sb.append(resourceTypeName);
            sb.append("\n");
            sb.append("\n");
            sb.append(repeat(" ", indent));
        }
    }

    private void generateOutputs(ClassDoc classDoc, StringBuilder sb, int indent) {
        writeAttributes(classDoc, sb, indent, OutputMode.OUTPUT_ONLY, true);
    }

    private String firstSentence(String commentText) {
        return commentText.split("\n")[0];
    }

    private String comment(String commentText, int indent) {
        StringBuilder sb = new StringBuilder();

        String[] parts = commentText.split("\n");
        if (parts.length > 1) {
            sb.append("\n");
            for (int i = 1; i < parts.length; i++) {
                sb.append("\n");
                sb.append(repeat(" ", indent));
                sb.append(trimLeadingSpaces(parts[i]));
            }
        }

        return sb.toString();
    }

    private String getDocGroupName(PackageDoc packageDoc) {
        for (AnnotationDesc annotationDesc : packageDoc.annotations()) {
            if (annotationDesc.annotationType().name().equals("DocGroup")) {
                return (String) annotationDesc.elementValues()[0].value().value();
            }
        }
        return null;
    }

    private String getResourceType(ClassDoc doc) {
        for (AnnotationDesc annotationDesc : doc.annotations()) {
            if (annotationDesc.annotationType().name().equals("Type")) {
                for (AnnotationDesc.ElementValuePair pair : annotationDesc.elementValues()) {
                    if (pair.element().name().equals("value")) {
                        return (String) pair.value().value();
                    }
                }
            }
        }
        return null;
    }

    private enum OutputMode {
        OUTPUT_ONLY,
        INCLUDE_OUTPUT,
        EXCLUDE_OUTPUT;
    }

    private enum ResourceType {
        RESOURCE,
        SUBRESOURCE;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    private String addValidationAnnotationMessage(MethodDoc methodDoc, String commentText) {

        String returnTypeName = methodDoc.returnType().typeName();

        boolean isCollection = returnTypeName.equals("Set") || returnTypeName.equals("List");

        // Exceptions for auto generating docs
        Set<String> noDocSet = new HashSet<>();
        Tag tag = Arrays.stream(methodDoc.tags()).filter(o -> o.name().equals("@no-doc")).findFirst().orElse(null);

        if (tag != null) {
            noDocSet = Arrays.stream(tag.text().split(",")).map(String::trim).collect(Collectors.toSet());
        }

        // Remove reference doc to be added later
        Matcher matcher = SEE_REF_DOC.matcher(commentText);
        String seeRefDoc = "";
        if (matcher.find()) {
            seeRefDoc = matcher.group();
            commentText = commentText.replace(seeRefDoc, "");
        }

        // Conflicts With
        if (isAnnotationPresent(methodDoc, ConflictsWith.class) && !noDocSet.contains("ConflictsWith")) {
            AnnotationDesc annotationDesc = getAnnotationDesc(methodDoc, ConflictsWith.class);

            List<String> conflictedFields = getAnnotationValues(annotationDesc);

            String ConflictedFieldString = String.join("``, ``", conflictedFields);

            // Replace last "," with an "or"
            if (conflictedFields.size() > 1) {
                ConflictedFieldString = String.format(
                    "Cannot be set if any of ``%s or%s`` is set.",
                    ConflictedFieldString.substring(0, ConflictedFieldString.lastIndexOf(",")),
                    ConflictedFieldString.substring(ConflictedFieldString.lastIndexOf(",") + 1));
            } else {
                ConflictedFieldString = String.format("Cannot be set if ``%s`` is set.", ConflictedFieldString);
            }

            commentText = String.format("%s %s", commentText, ConflictedFieldString);
        }

        if (isAnnotationPresent(methodDoc, ValidStrings.class) && noDocSet.contains("ValidStrings")) {
            System.out.println(" --> start");
            System.out.println(" --> start" + methodDoc.returnType());
            if (methodDoc.returnType().isPrimitive()) {
                MethodDoc[] enumConstants = methodDoc.getClass().getEnumConstants();
                if (enumConstants != null) {
                    System.out.println("--> " + enumConstants[0].toString());
                }
            }
        }

        // Valid Strings
        if (isAnnotationPresent(methodDoc, ValidStrings.class) && !noDocSet.contains("ValidStrings")) {
            AnnotationDesc annotationDesc = getAnnotationDesc(methodDoc, ValidStrings.class);

            List<String> validStringList = getAnnotationValues(annotationDesc);

            String validString = String.join("``, ``", validStringList);

            // Replace last "," with an "or"
            if (validStringList.size() > 1) {
                validString = String.format(
                    "Valid values are ``%s %s%s``.",
                    validString.substring(0, validString.lastIndexOf(",")),
                    (isCollection ? "and" : "or"),
                    validString.substring(validString.lastIndexOf(",") + 1));
            } else {
                validString = String.format("Currently the only supported value is ``%s``.", validString);
            }

            commentText = String.format("%s %s", commentText, validString);
        }

        // Valid Numbers
        if (isAnnotationPresent(methodDoc, ValidNumbers.class) && !noDocSet.contains("ValidNumbers")) {
            AnnotationDesc annotationDesc = getAnnotationDesc(methodDoc, ValidNumbers.class);

            List<String> validNumberList = getAnnotationValues(annotationDesc);

            String validNumber = String.join("``, ``", validNumberList);

            // Replace last "," with an "or"
            if (validNumberList.size() > 1) {
                validNumber = String.format(
                    "Valid values are ``%s %s%s`.",
                    validNumber.substring(0, validNumber.lastIndexOf(",")),
                    (isCollection ? "and" : "or"),
                    validNumber.substring(validNumber.lastIndexOf(",") + 1));
            } else {
                validNumber = String.format("Currently the only supported value is ``%s``.", validNumber);
            }

            commentText = String.format("%s %s", commentText, validNumber);
        }

        // Depends on
        if (isAnnotationPresent(methodDoc, DependsOn.class) && !noDocSet.contains("DependsOn")) {
            AnnotationDesc annotationDesc = getAnnotationDesc(methodDoc, DependsOn.class);

            List<String> dependentFields = getAnnotationValues(annotationDesc);

            String dependentFieldString = String.join("``, ``", dependentFields);

            // Replace last "," with an "and"
            if (dependentFields.size() > 1) {
                dependentFieldString = String.format(
                    "Can only be set if all of ``%s and%s`` is set.",
                    dependentFieldString.substring(0, dependentFieldString.lastIndexOf(",")),
                    dependentFieldString.substring(dependentFieldString.lastIndexOf(",") + 1));
            } else {
                dependentFieldString = String.format("Can only be set if ``%s`` is set.", dependentFieldString);
            }

            commentText = String.format("%s %s", commentText, dependentFieldString);
        }

        // Max
        if (isAnnotationPresent(methodDoc, Max.class) && !noDocSet.contains("Max")) {
            AnnotationDesc annotationDesc = getAnnotationDesc(methodDoc, Max.class);

            String maxValue = decimalTrimmed(getAnnotationValue(annotationDesc));

            String maxFieldString = String.format("Maximum allowed value is ``%s``.", maxValue);

            commentText = String.format("%s %s", commentText, maxFieldString);
        }

        // Min
        if (isAnnotationPresent(methodDoc, Min.class) && !noDocSet.contains("Min")) {
            AnnotationDesc annotationDesc = getAnnotationDesc(methodDoc, Min.class);

            String maxValue = decimalTrimmed(getAnnotationValue(annotationDesc));

            String minFieldString = String.format("Minimum allowed value is ``%s``.", maxValue);

            commentText = String.format("%s %s", commentText, minFieldString);
        }

        // CollectionMax
        if (isAnnotationPresent(methodDoc, CollectionMax.class) && !noDocSet.contains("CollectionMax")) {
            AnnotationDesc annotationDesc = getAnnotationDesc(methodDoc, CollectionMax.class);

            String collectionMaxValue = decimalTrimmed(getAnnotationValue(annotationDesc));

            String collectionMaxFieldString = String.format("Maximum allowed items are ``%s``.", collectionMaxValue);

            commentText = String.format("%s %s", commentText, collectionMaxFieldString);
        }

        // CollectionMin
        if (isAnnotationPresent(methodDoc, CollectionMin.class) && !noDocSet.contains("CollectionMin")) {
            AnnotationDesc annotationDesc = getAnnotationDesc(methodDoc, CollectionMin.class);

            String collectionMinValue = decimalTrimmed(getAnnotationValue(annotationDesc));

            String collectionMinFieldString = String.format("Minimum required items are ``%s``.", collectionMinValue);

            commentText = String.format("%s %s", commentText, collectionMinFieldString);
        }

        // Range
        if (isAnnotationPresent(methodDoc, Range.class) && !noDocSet.contains("Range")) {
            AnnotationDesc annotationDesc = getAnnotationDesc(methodDoc, Range.class);

            String maxValue = decimalTrimmed(getAnnotationValue(annotationDesc, "max"));
            String minValue = decimalTrimmed(getAnnotationValue(annotationDesc, "min"));

            String rangeFieldString = String.format("Valid values are between ``%s`` to ``%s``.", minValue, maxValue);

            commentText = String.format("%s %s", commentText, rangeFieldString);
        }

        // Ranges
        if (isAnnotationPresent(methodDoc, Ranges.class) && !noDocSet.contains("Range")
            && !noDocSet.contains("Ranges")) {
            AnnotationDesc annotationDesc = getAnnotationDesc(methodDoc, Ranges.class);

            List<AnnotationDesc> annotationDescs = getAnnotationDescs(annotationDesc);
            List<String> rangeFieldStrings = new ArrayList<>();

            annotationDescs.forEach(o -> {
                String maxValue = decimalTrimmed(getAnnotationValue(o, "max"));
                String minValue = decimalTrimmed(getAnnotationValue(o, "min"));

                rangeFieldStrings.add(String.format("``%s`` to ``%s``", minValue, maxValue));
            });

            String join = String.join(", ", rangeFieldStrings);
            String rangesFieldString = String.format(
                "Valid values are between %s and%s.",
                join.substring(0, join.lastIndexOf(",")),
                join.substring(join.lastIndexOf(",") + 1));

            commentText = String.format("%s %s", commentText, rangesFieldString);
        }

        // Regex
        if (isAnnotationPresent(methodDoc, Regex.class) && !noDocSet.contains("Regex")) {
            AnnotationDesc annotationDesc = getAnnotationDesc(methodDoc, Regex.class);

            String regexValue = StringUtils.escapeJava(getAnnotationValue(annotationDesc, "value"));
            String regexMessage = getAnnotationValue(annotationDesc, "message");

            String validRegexMessage = !ObjectUtils.isBlank(regexMessage)
                ? String.format("Must be %s.", regexMessage)
                : "";

            String regexFieldString = String.format(
                "%s Valid values satisfy the regex: ``[%s]``.",
                validRegexMessage,
                regexValue);

            commentText = String.format("%s %s", commentText, regexFieldString);
        }

        // Regexes
        if (isAnnotationPresent(methodDoc, Regexes.class) && !noDocSet.contains("Regex")
            && !noDocSet.contains("Regexes")) {
            AnnotationDesc annotationDesc = getAnnotationDesc(methodDoc, Regexes.class);

            List<AnnotationDesc> annotationDescs = getAnnotationDescs(annotationDesc);
            List<String> regexFieldStrings = new ArrayList<>();

            annotationDescs.forEach(o -> {
                String regexValue = StringUtils.escapeJava(getAnnotationValue(o, "value"));
                String regexMessage = getAnnotationValue(o, "message");

                String validRegexMessage = !ObjectUtils.isBlank(regexMessage)
                    ? String.format("Must be %s.", regexMessage)
                    : "";

                regexFieldStrings.add(String.format(
                    "%s Valid values satisfy the regex: ``[%s]``.",
                    validRegexMessage,
                    regexValue));
            });

            String rangesFieldString = String.format(
                "Valid values satisfy one of the following regexes: \n\n %s",
                String.join("\n", regexFieldStrings));

            commentText = String.format("%s %s", commentText, rangesFieldString);
        }

        // See Ref Doc
        // Has to be before Required
        if (!ObjectUtils.isBlank(seeRefDoc)) {
            commentText = String.format("%s %s", commentText, seeRefDoc);
        }

        // Required
        // Has to be the last one
        if (isAnnotationPresent(methodDoc, Required.class)) {
            commentText = String.format("%s %s", commentText, "(Required)");
        }

        commentText = commentText.replaceAll("\\.\\.", ".")
            .replaceAll("@\\|bold\\s+", "``")
            .replaceAll("\\|@", "``");

        return commentText;
    }

    private boolean isAnnotationPresent(MethodDoc methodDoc, Class<?> annotationClass) {
        return Arrays.stream(methodDoc.annotations())
            .anyMatch(o -> o.annotationType().qualifiedName().equals(annotationClass.getName()));
    }

    private AnnotationDesc getAnnotationDesc(MethodDoc methodDoc, Class<?> annotationClass) {
        return Arrays.stream(methodDoc.annotations())
            .filter(o -> o.annotationType().qualifiedName().equals(annotationClass.getName()))
            .findFirst()
            .orElse(null);
    }

    private List<String> getAnnotationValues(AnnotationDesc annotationDesc) {
        AnnotationDesc.ElementValuePair elementValuePair = annotationDesc.elementValues()[0];
        AnnotationValue[] values = (AnnotationValue[]) elementValuePair.value().value();

        return Arrays.stream(values).map(o -> o.value().toString()).collect(Collectors.toList());
    }

    private String getAnnotationValue(AnnotationDesc annotationDesc) {
        return getAnnotationValue(annotationDesc, "value");
    }

    private String getAnnotationValue(AnnotationDesc annotationDesc, String elementName) {
        String returnValue = "";

        AnnotationDesc.ElementValuePair elementValuePair = Arrays.stream(annotationDesc.elementValues())
            .filter(o -> o.element().name().equals(elementName))
            .findFirst()
            .orElse(null);

        if (elementValuePair != null) {
            Object value = elementValuePair.value().value();
            if (value instanceof Number) {
                if (value instanceof Integer) {
                    returnValue = String.format("%d", value);
                } else {
                    returnValue = String.format("%f", value);
                }
            } else {
                returnValue = elementValuePair.value().value().toString();
            }
        }

        return returnValue;
    }

    private List<AnnotationDesc> getAnnotationDescs(AnnotationDesc annotationDesc) {
        AnnotationDesc.ElementValuePair elementValuePair = annotationDesc.elementValues()[0];
        AnnotationValue[] values = (AnnotationValue[]) elementValuePair.value().value();

        return Arrays.stream(values).map(o -> (AnnotationDesc) o.value()).collect(Collectors.toList());
    }

    private String decimalTrimmed(String num) {
        String result = num;
        String[] split = num.split("\\.");
        if (split.length == 2) {
            String decimal = split[1].replaceAll("0", "");
            if (ObjectUtils.isBlank(decimal)) {
                result = split[0];
            }
        }

        return result;
    }
}
