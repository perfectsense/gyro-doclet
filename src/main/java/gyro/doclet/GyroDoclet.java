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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.Doclet;
import com.sun.javadoc.PackageDoc;
import com.sun.javadoc.RootDoc;

public class GyroDoclet extends Doclet {

    private static final String LINK_PATTERN = "Resource_Query_Link_%s_%s_";

    protected static final String RESOURCE_LINK_PATTERN = LINK_PATTERN + "Resource";

    protected static final String QUERY_LINK_PATTERN = LINK_PATTERN + "Query";

    public static boolean start(RootDoc root) {
        // Generate rst file for each resource.
        // Generate index for each group (i.e. java package) of resources.
        // Generate index for all groups.

        String outputDirectory = ".";
        for (int i = 0; i < root.options().length; i++) {
            String[] optionArray = root.options()[i];
            String option = optionArray[0];

            if (option.equals("-d")) {
                outputDirectory = optionArray[1];
            }

        }

        // group -> "resource -> rst"
        Map<String, Map<String, String>> docs = new HashMap<>();
        String providerPackage = "";

        for (ClassDoc doc : root.classes()) {
            if (doc.isAbstract() || (!ResourceDocGenerator.isResource(doc) && !ResourceDocGenerator.isFinder(doc))) {
                continue;
            }

            ResourceDocGenerator generator = new ResourceDocGenerator(root, doc, ResourceDocGenerator.isFinder(doc));

            Map<String, String> groupDocs = docs.computeIfAbsent(generator.getGroupName(), m -> new HashMap());

            groupDocs.put(generator.getName(), generator.generate());

            if (providerPackage.equals("")) {
                providerPackage = generator.getProviderPackage();
            }
        }

        /*
        AWS Provider
        ------------

        .. toctree::
            :maxdepth: 1

           autoscaling-groups/index
           ec2/index
         */

        List<String> groupDirs = new ArrayList<>();
        for (String group : docs.keySet()) {
            if (group != null) {
                String groupDir = group.toLowerCase().replaceAll(" ", "-");

                new File(outputDirectory + File.separator + groupDir).mkdirs();

                // Output individual resource files.
                Map<String, String> resources = docs.get(group);
                for (String resource : resources.keySet()) {
                    String rst = resources.get(resource);

                    if(!resource.endsWith("-finder")) {

                        String finderResource = resource + "-finder";
                        if (resources.containsKey(finderResource)) {
                            String finderRst = resources.get(finderResource);
                            String resourceLink = String.format(RESOURCE_LINK_PATTERN, group, resource);
                            String queryLink = String.format(QUERY_LINK_PATTERN, group, resource);

                            StringBuilder sb  = new StringBuilder();
                            rst = sb.append(".. _").append(resourceLink).append(":")
                                .append("\n\n")
                                .append(".. rst-class:: .query-resource-link")
                                .append("\n")
                                .append(":ref:`Query <").append(queryLink).append(">`")
                                .append("\n\n")
                                .append(rst).toString();

                            sb = new StringBuilder();
                            finderRst = sb.append(".. _").append(queryLink).append(":")
                                .append("\n\n")
                                .append(".. rst-class:: .query-resource-link")
                                .append("\n")
                                .append(":ref:`Back to resource <").append(resourceLink).append(">`")
                                .append("\n\n")
                                .append(finderRst).toString();

                            //Resource
                            try (FileWriter writer = new FileWriter(outputDirectory + File.separator + groupDir + File.separator + resource + ".rst")) {
                                writer.write(rst);
                            } catch (IOException ioe) {
                                ioe.printStackTrace();
                            }

                            //Finder
                            try (FileWriter writer = new FileWriter(outputDirectory + File.separator + groupDir + File.separator + finderResource + ".rst")) {
                                writer.write(finderRst);
                            } catch (IOException ioe) {
                                ioe.printStackTrace();
                            }

                        } else { //No finder
                            try (FileWriter writer = new FileWriter(outputDirectory + File.separator + groupDir + File.separator + resource + ".rst")) {
                                writer.write(rst);
                            } catch (IOException ioe) {
                                ioe.printStackTrace();
                            }
                        }
                    }
                }

                // Output group index
                try (FileWriter writer = new FileWriter(outputDirectory + File.separator + groupDir + File.separator + "index.rst")) {
                    writer.write(generateGroupIndex(group, resources));
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                }

                groupDirs.add(groupDir);
            }
        }

        // Output provider index
        StringBuilder providerIndex = new StringBuilder();
        PackageDoc rootPackageDoc = root.packageNamed(providerPackage);

        providerIndex.append(trimLeadingSpace(rootPackageDoc.commentText()).replace("{@literal @}", "@"));
        providerIndex.append("\n\nResources\n");
        providerIndex.append("+++++++++\n");
        providerIndex.append("\n\n");
        providerIndex.append(".. toctree::\n");
        providerIndex.append("    :maxdepth: 1\n\n");

        Collections.sort(groupDirs);

        for (String groupDir : groupDirs) {
            providerIndex.append("    ");
            providerIndex.append(groupDir);
            providerIndex.append("/index\n");
        }

        try (FileWriter writer = new FileWriter(outputDirectory + File.separator + "index.rst")) {
            writer.write(providerIndex.toString());
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }

        return true;
    }

    public static int optionLength(String option) {
        if (option.equals("-d")) {
            return 2;
        }

        return 0;
    }

    private static String generateGroupIndex(String groupName, Map<String, String> resources) {
        StringBuilder sb = new StringBuilder();

        /*
        Autoscaling Groups
        ==================

        .. toctree::

            auto-scaling-group
            launch-configuration
        */

        sb.append(groupName).append("\n");
        sb.append(ResourceDocGenerator.repeat("=", groupName.length()));
        sb.append("\n\n");
        sb.append(".. toctree::");
        sb.append("\n");
        sb.append("    :maxdepth: 1");
        sb.append("\n\n");

        List<String> keys = new ArrayList<>(resources.keySet());
        Collections.sort(keys);

        for (String resource : keys) {
            if (resource != null && !resource.endsWith("-finder")) {
                sb.append("    ").append(resource);
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private static String trimLeadingSpace(String comment) {
        StringBuilder sb = new StringBuilder();

        String[] parts = comment.split("\n");
        if (parts.length > 1) {
            for (int i = 0; i < parts.length; i++) {
                sb.append(parts[i].replaceFirst(" ", ""));
                sb.append("\n");
            }
        }

        return sb.toString();

    }

}
