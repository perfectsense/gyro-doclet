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

import com.google.common.base.CaseFormat;
import com.sun.javadoc.AnnotationDesc;
import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.PackageDoc;
import com.sun.javadoc.RootDoc;
import com.sun.javadoc.Tag;

public class ResourceDocGenerator {

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
        for (AnnotationDesc annotationDesc : packageDoc.annotations()) {
            if (annotationDesc.annotationType().name().equals("DocGroup")) {
                groupName = (String) annotationDesc.elementValues()[0].value().value();
            }
        }

        for (AnnotationDesc annotationDesc : doc.annotations()) {
            if (annotationDesc.annotationType().name().equals("Type")) {
                for (AnnotationDesc.ElementValuePair pair : annotationDesc.elementValues()) {
                    if (pair.element().name().equals("value")) {
                        name = (String) pair.value().value();
                        break;
                    }
                }
            }
        }

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

    public static String trim(String s) {
        StringBuilder sb = new StringBuilder();

        for (String line : s.split("\n")) {
            if (line.startsWith(" ")) {
                sb.append(line.substring(1));
            } else {
                sb.append(line);
            }
            sb.append("\n");
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
        return String.format("%s::%s", namespace, name.replace("-finder",""));
    }

    private void generateHeader(StringBuilder sb) {
        sb.append(resourceName());
        sb.append("\n");
        sb.append(repeat("=", resourceName().length()));
        sb.append("\n\n");
        sb.append(trim(doc.commentText()));
        sb.append("\n\n");
    }

    private boolean writeAttributes(ClassDoc classDoc, StringBuilder sb, int indent, boolean includeOutput) {
        return writeAttributes(classDoc, sb, indent, includeOutput, true);
    }

    private boolean writeAttributes(ClassDoc classDoc, StringBuilder sb, int indent, boolean includeOutput, boolean tableFormat) {
        boolean hadOutputs = false;

        // Output superclass attributes.
        if (classDoc.superclass() != null && (!classDoc.superclass().name().equals("Resource") || !classDoc.superclass().name().equals("Finder"))) {
            hadOutputs = writeAttributes(classDoc.superclass(), sb, indent, includeOutput, tableFormat);
        }

        // Read each method that contains a comment.
        for (MethodDoc methodDoc : classDoc.methods()) {
            if (methodDoc.commentText() != null && methodDoc.commentText().length() > 0) {
                String attributeName = methodDoc.name();
                attributeName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, attributeName).replaceFirst("get-", "");

                String attributeSubresourceClass = "";
                boolean attributeIsSubresource = false;
                boolean attributeIsOutput = false;

                for (AnnotationDesc annotationDesc : methodDoc.annotations()) {
                    if (annotationDesc.annotationType().name().equals("Output")) {
                        attributeIsOutput = true;
                    }
                }

                for (Tag tag : methodDoc.tags()) {
                    if (tag.name().equals("@subresource")) {
                        attributeSubresourceClass = tag.text();
                        attributeIsSubresource = true;
                    } else if (tag.name().equals("@output")) {
                        attributeIsOutput = true;
                    }
                }

                if (attributeIsSubresource && (includeOutput || !attributeIsOutput)) {
                    if (tableFormat) {
                        sb.append(repeat(" ", indent));
                        sb.append("    * - ");
                        sb.append(String.format(":attribute:`%s` :subresource:`subresource`", attributeName));
                        sb.append("\n");
                        sb.append(repeat(" ", indent + 6));
                        sb.append("- ");
                        sb.append(firstSentence(methodDoc.commentText()));
                        sb.append("\n\n");
                    } else {
                        sb.append(repeat(" ", indent));
                        sb.append(".. rst-class:: subresource\n");
                        sb.append(repeat(" ", indent));
                        sb.append(attributeName);
                        sb.append("\n");
                        sb.append(repeat(" ", indent + 4));
                        sb.append(firstSentence(methodDoc.commentText()));
                        sb.append("\n\n");
                    }

                    ClassDoc subresourceDoc = root.classNamed(attributeSubresourceClass);
                    if (subresourceDoc != null) {
                        writeAttributes(subresourceDoc, sb, indent + 8, includeOutput, false);
                    }
                } else if (includeOutput || !attributeIsOutput) {
                    writeAttribute(sb, methodDoc, indent, tableFormat);
                }

                if (attributeIsOutput) {
                    hadOutputs = true;
                }
            }
        }

        return hadOutputs;
    }

    private void generateOutputs(ClassDoc classDoc, StringBuilder sb, int indent) {
        generateOutputs(classDoc, sb, indent, true);
    }

    private void generateOutputs(ClassDoc classDoc, StringBuilder sb, int indent, boolean tableFormat) {
        // Output superclass attributes.
        if (classDoc.superclass() != null && (!classDoc.superclass().name().equals("Resource") || !classDoc.superclass().name().equals("Finder"))) {
            generateOutputs(classDoc.superclass(), sb, indent, tableFormat);
        }

        for (MethodDoc methodDoc : classDoc.methods()) {
            if (methodDoc.commentText() != null && methodDoc.commentText().length() > 0) {
                String attributeName = methodDoc.name();
                attributeName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, attributeName).replaceFirst("get-", "");

                boolean isSubresource = false;
                boolean isOutput = false;

                for (AnnotationDesc annotationDesc : methodDoc.annotations()) {
                    if (annotationDesc.annotationType().name().equals("Output")) {
                        isOutput = true;
                    }
                }

                if (isOutput) {
                    for (Tag tag : methodDoc.tags()) {
                        if (tag.name().equals("@subresource")) {
                            if (tableFormat) {
                                sb.append(repeat(" ", indent));
                                sb.append("    * - ");
                                sb.append(String.format(":attribute:`%s` :subresource:`subresource`", attributeName));
                                sb.append("\n");
                                sb.append(repeat(" ", indent + 6));
                                sb.append("- ");
                                sb.append(firstSentence(methodDoc.commentText()));
                                sb.append("\n\n");
                            } else {
                                sb.append(repeat(" ", indent));
                                sb.append(".. rst-class:: subresource\n");
                                sb.append(repeat(" ", indent));
                                sb.append(attributeName);
                                sb.append("\n");
                                sb.append(repeat(" ", indent + 4));
                                sb.append(firstSentence(methodDoc.commentText()));
                                sb.append("\n\n");
                            }

                            ClassDoc subresourceDoc = root.classNamed(tag.text());
                            if (subresourceDoc != null) {
                                generateOutputs(subresourceDoc, sb, indent + 8, false);
                            }

                            isSubresource = true;
                        }
                    }

                    if (!isSubresource) {
                        writeAttribute(sb, methodDoc, indent, tableFormat);
                    }
                }
            }
        }
    }

    private void writeAttribute(StringBuilder sb, MethodDoc methodDoc, int indent) {
        writeAttribute(sb, methodDoc, indent, true);
    }

    private void writeAttribute(StringBuilder sb, MethodDoc methodDoc, int indent, boolean tableFormat) {
        String attributeName = methodDoc.name();
        attributeName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, attributeName).replaceFirst("get-", "");
        String commentText = methodDoc.commentText();

        if (tableFormat) {
            sb.append(repeat(" ", indent + 4));
            sb.append(String.format("* - %s\n", attributeName));
            sb.append(repeat(" ", indent + 6));
            sb.append("- ");
            sb.append(firstSentence(commentText));
        } else {
            sb.append(repeat(" ", indent));
            sb.append(String.format("%s\n", attributeName));
            sb.append(repeat(" ", indent + 4));
            sb.append(firstSentence(commentText));
        }

        String rest = comment(commentText, indent + (tableFormat ? 8 : 4));
        if (rest != null && rest.length() > 0) {
            sb.append(rest);
        }

        sb.append("\n\n");
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
                sb.append(parts[i].replaceAll("^\\s+", ""));
            }
        }

        return sb.toString();
    }

}
