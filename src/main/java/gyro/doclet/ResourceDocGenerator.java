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

import java.util.Optional;
import java.util.regex.Pattern;

import com.google.common.base.CaseFormat;
import com.sun.javadoc.AnnotationDesc;
import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.PackageDoc;
import com.sun.javadoc.RootDoc;
import com.sun.javadoc.Tag;

public class ResourceDocGenerator {

    private static final Pattern LEADING_WHITE_SPACE = Pattern.compile("^\\s?");
    private static final Pattern LEADING_WHITE_SPACES = Pattern.compile("^\\s+");

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
            name = name + "-finder";
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

        System.out.println("Generating documentation for: " + resourceName());

        generateHeader(sb);

        sb.append("Attributes\n");
        sb.append(repeat("-", 10));
        sb.append("\n\n");

        sb.append(".. role:: attribute\n\n");
        sb.append(".. role:: resource\n\n");
        sb.append(".. role:: subresource\n\n");

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
        ClassDoc superClass = classDoc.superclass();
        while (superClass != null) {
            if (superClass.name().equals("Resource")) {
                isResource = true;
                break;
            }

            superClass = superClass.superclass();
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
        return String.format("%s::%s", namespace, name.replace("-finder",""));
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
        return writeAttributes(classDoc, sb, indent, includeOutput ? OutputMode.INCLUDE_OUTPUT : OutputMode.EXCLUDE_OUTPUT, true);
    }

    private boolean writeAttributes(ClassDoc classDoc, StringBuilder sb, int indent, OutputMode outputMode, boolean tableFormat) {
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
                String attributeName = methodDoc.name();
                attributeName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, attributeName).replaceFirst("get-", "");

                String attributeSubresourceClass = "";
                boolean attributeIsOutput = false;
                ResourceType attributeResourceType = null;
                StringBuilder resourceLinkBuilder = new StringBuilder();

                for (AnnotationDesc annotationDesc : methodDoc.annotations()) {
                    if (annotationDesc.annotationType().name().equals("Output")) {
                        attributeIsOutput = true;
                    }
                }

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
                    writeAttribute(sb, attributeName, attributeResourceType, resourceLinkBuilder, commentText, indent, tableFormat);

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

    private void writeAttribute(StringBuilder sb, String attributeName, ResourceType resourceType, StringBuilder link, String commentText, int indent, boolean tableFormat) {
        String resourceTypeName = Optional.ofNullable(resourceType)
            .map(ResourceType::toString)
            .orElse(null);

        if (tableFormat) {
            sb.append(repeat(" ", indent + 4));
            sb.append("* - ");
            sb.append(String.format(":attribute:`%s`", attributeName));

            if (resourceTypeName != null) {
                sb.append(String.format(" :%s:`%s`", resourceTypeName, resourceTypeName));
            }
            sb.append("\n");
            sb.append(repeat(" ", indent + 6));
            sb.append("- ");

            writeLink(sb, link, resourceTypeName, indent + 8);
        } else {
            sb.append(repeat(" ", indent));

            if (resourceTypeName != null) {
                sb.append(String.format(".. rst-class:: %s\n", resourceTypeName));
                sb.append(repeat(" ", indent));
            }
            sb.append(attributeName);
            sb.append("\n");
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
}
