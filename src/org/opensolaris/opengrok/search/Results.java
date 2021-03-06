/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2005, 2018, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright 2011 Jens Elkner.
 * Portions Copyright (c) 2017-2018, Chris Fraire <cfraire@me.com>.
 */

package org.opensolaris.opengrok.search;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.json.simple.JSONArray;
import org.opensolaris.opengrok.analysis.Definitions;
import org.opensolaris.opengrok.analysis.FileAnalyzer.Genre;
import org.opensolaris.opengrok.analysis.Scopes;
import org.opensolaris.opengrok.configuration.Project;
import org.opensolaris.opengrok.configuration.RuntimeEnvironment;
import org.opensolaris.opengrok.history.HistoryException;
import org.opensolaris.opengrok.logger.LoggerFactory;
import org.opensolaris.opengrok.util.IOUtils;
import org.opensolaris.opengrok.web.Prefix;
import org.opensolaris.opengrok.web.SearchHelper;
import org.opensolaris.opengrok.web.Util;

import static org.opensolaris.opengrok.web.messages.MessagesContainer.MESSAGES_MAIN_PAGE_TAG;

/**
 * @author Chandan slightly rewritten by Lubos Kosco
 */
public final class Results {

    private static final Logger LOGGER = LoggerFactory.getLogger(Results.class);

    private Results() {
        // Util class, should not be constructed
    }

    /**
     * Create a has map keyed by the directory of the document found.
     *
     * @param searcher searcher to use.
     * @param hits hits produced by the given searcher's search
     * @param startIdx the index of the first hit to check
     * @param stopIdx the index of the last hit to check
     * @return a (directory, hitDocument) hashmap
     * @throws CorruptIndexException
     * @throws IOException
     */
    private static Map<String, ArrayList<Integer>> createMap(
        IndexSearcher searcher, ScoreDoc[] hits, int startIdx, long stopIdx)
            throws CorruptIndexException, IOException {

        LinkedHashMap<String, ArrayList<Integer>> dirHash =
                new LinkedHashMap<>();
        for (int i = startIdx; i < stopIdx; i++) {
            int docId = hits[i].doc;
            Document doc = searcher.doc(docId);

            String rpath = doc.get(QueryBuilder.PATH);
            if (rpath == null) {
                continue;
            }

            String parent = rpath.substring(0, rpath.lastIndexOf('/'));
            ArrayList<Integer> dirDocs = dirHash.get(parent);
            if (dirDocs == null) {
                dirDocs = new ArrayList<>();
                dirHash.put(parent, dirDocs);
            }
            dirDocs.add(docId);
        }
        return dirHash;
    }

    private static String getTags(File basedir, String path, boolean compressed) {
        char[] content = new char[1024 * 8];
        try (HTMLStripCharFilter r = new HTMLStripCharFilter(getXrefReader(basedir, path, compressed))) {
            int len = r.read(content);
            return new String(content, 0, len);
        } catch (Exception e) {
            LOGGER.log(
                    Level.WARNING, "An error reading tags from " + basedir + path
                    + (compressed ? ".gz" : ""), e);
        }
        return "";
    }

    /** Return a reader for the specified xref file. */
    private static Reader getXrefReader(
                    File basedir, String path, boolean compressed)
            throws IOException {
        /**
         * For backward compatibility, read the OpenGrok-produced document
         * using the system default charset.
         */
        if (compressed) {
            return new BufferedReader(IOUtils.createBOMStrippedReader(
                    new GZIPInputStream(new FileInputStream(new File(basedir, path + ".gz")))));
        } else {
            return new BufferedReader(IOUtils.createBOMStrippedReader(
                    new FileInputStream(new File(basedir, path))));
        }
    }

    /**
     * Prints out results in html form. The following search helper fields are
     * required to be properly initialized: <ul>
     * <li>{@link SearchHelper#dataRoot}</li>
     * <li>{@link SearchHelper#contextPath}</li>
     * <li>{@link SearchHelper#searcher}</li> <li>{@link SearchHelper#hits}</li>
     * <li>{@link SearchHelper#historyContext} (ignored if {@code null})</li>
     * <li>{@link SearchHelper#sourceContext} (ignored if {@code null})</li>
     * <li>{@link SearchHelper#summarizer} (if sourceContext is not
     * {@code null})</li> <li>{@link SearchHelper#compressed} (if sourceContext
     * is not {@code null})</li> <li>{@link SearchHelper#sourceRoot} (if
     * sourceContext or historyContext is not {@code null})</li> </ul>
     *
     * @param out write destination
     * @param sh search helper which has all required fields set
     * @param start index of the first hit to print
     * @param end index of the last hit to print
     * @throws HistoryException
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public static void prettyPrint(Writer out, SearchHelper sh, int start,
            long end)
            throws HistoryException, IOException, ClassNotFoundException {
        Project p;
        String ctxE = Util.URIEncodePath(sh.contextPath);
        String xrefPrefix = sh.contextPath + Prefix.XREF_P;
        String morePrefix = sh.contextPath + Prefix.MORE_P;
        String xrefPrefixE = ctxE + Prefix.XREF_P;
        File xrefDataDir = new File(sh.dataRoot, Prefix.XREF_P.toString());

        RuntimeEnvironment env = RuntimeEnvironment.getInstance();

        boolean evenRow = true;
        out.write("<tbody class=\"search-result\">");
        for (Map.Entry<String, ArrayList<Integer>> entry :
                createMap(sh.searcher, sh.hits, start, end).entrySet()) {
            String parent = entry.getKey();
            out.write("<tr class=\"dir\"><td colspan=\"3\"><a href=\"");
            out.write(xrefPrefixE);
            out.write(Util.URIEncodePath(parent));
            out.write("/\">");
            out.write(htmlize(parent));
            out.write("/</a>");
            if (sh.desc != null) {
                out.write(" - <i>");
                out.write(htmlize(sh.desc.get(parent)));
                out.write("</i>");
            }
            JSONArray messages;
            if ((p = Project.getProject(parent)) != null
                    && (messages = Util.messagesToJson(p, MESSAGES_MAIN_PAGE_TAG)).size() > 0) {
                out.write(" <a href=\"" + xrefPrefix + "/" + p.getName() + "\">");
                out.write("<span class=\"important-note important-note-rounded\" data-messages='" + messages + "'>!</span>");
                out.write("</a>");
            }

            int tabSize = sh.getTabSize(p);
            PrintPlainFinalArgs fargs = new PrintPlainFinalArgs(out, sh, env,
                xrefPrefix, tabSize, morePrefix);

            out.write("</td></tr>");
            for (int docId : entry.getValue()) {
                Document doc = sh.searcher.doc(docId);
                String rpath = doc.get(QueryBuilder.PATH);
                String rpathE = Util.URIEncodePath(rpath);
                if (evenRow) {
                    out.write("<tr class=\"search-result-even-row\">");
                } else {
                    out.write("<tr>");
                }
                evenRow = !evenRow;
                Util.writeHAD(out, sh.contextPath, rpathE, false);
                out.write("<td class=\"f\"><a href=\"");
                out.write(xrefPrefixE);
                out.write(rpathE);
                out.write("\"");
                if (env.isLastEditedDisplayMode()) {
                    printLastEditedDate(out, doc);
                }
                out.write(">");
                out.write(htmlize(rpath.substring(rpath.lastIndexOf('/') + 1)));
                out.write("</a>");
                out.write("</td><td><code class=\"con\">");
                if (sh.sourceContext != null) {
                    Genre genre = Genre.get(doc.get("t"));
                    if (Genre.XREFABLE == genre && sh.summarizer != null) {
                        String xtags = getTags(xrefDataDir, rpath, sh.compressed);
                        // FIXME use Highlighter from lucene contrib here,
                        // instead of summarizer, we'd also get rid of
                        // apache lucene in whole source ...
                        out.write(sh.summarizer.getSummary(xtags).toString());
                    } else if (Genre.HTML == genre && sh.summarizer != null) {
                        String htags = getTags(sh.sourceRoot, rpath, false);
                        out.write(sh.summarizer.getSummary(htags).toString());
                    } else if (genre == Genre.PLAIN) {
                        printPlain(fargs, doc, docId, rpath);
                    }
                }

                if (sh.historyContext != null) {
                    sh.historyContext.getContext(new File(sh.sourceRoot, rpath),
                            rpath, out, sh.contextPath);
                }
                out.write("</code></td></tr>\n");
            }
        }
        out.write("</tbody>");
    }

    private static void printLastEditedDate(final Writer out, final Document doc) throws IOException {
        try {
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
            String dd = df.format(DateTools.stringToDate(doc.get("date")));
            out.write(" class=\"result-annotate\" title=\"");
            out.write("Last modified: ");
            out.write(dd);
            out.write("\"");
        } catch (ParseException ex) {
            LOGGER.log(Level.WARNING, "An error parsing date information", ex);
        }
    }

    private static void printPlain(PrintPlainFinalArgs fargs, Document doc,
        int docId, String rpath) throws ClassNotFoundException, IOException {

        fargs.shelp.sourceContext.toggleAlt();

        boolean didPresentNew = fargs.shelp.sourceContext.getContext2(fargs.env,
            fargs.shelp.searcher, docId, fargs.out, fargs.xrefPrefix,
            fargs.morePrefix, true, fargs.tabSize);

        if (!didPresentNew) {
            /**
             * Fall back to the old view, which re-analyzes text using
             * PlainLinetokenizer. E.g., when source code is updated (thus
             * affecting timestamps) but re-indexing is not yet complete.
             */
            Definitions tags = null;
            IndexableField tagsField = doc.getField(QueryBuilder.TAGS);
            if (tagsField != null) {
                tags = Definitions.deserialize(tagsField.binaryValue().bytes);
            }
            Scopes scopes;
            IndexableField scopesField = doc.getField(QueryBuilder.SCOPES);
            if (scopesField != null) {
                scopes = Scopes.deserialize(scopesField.binaryValue().bytes);
            } else {
                scopes = new Scopes();
            }
            boolean isDefSearch = fargs.shelp.builder.isDefSearch();
            // SRCROOT is read with UTF-8 as a default.
            try (Reader r = IOUtils.createBOMStrippedReader(new FileInputStream(
                    new File(fargs.shelp.sourceRoot, rpath)),
                    StandardCharsets.UTF_8.name())) {
                fargs.shelp.sourceContext.getContext(r, fargs.out,
                    fargs.xrefPrefix, fargs.morePrefix, rpath, tags, true,
                    isDefSearch, null, scopes);
            }
        }
    }

    private static String htmlize(String raw) {
        return Util.htmlize(raw);
    }

    private static class PrintPlainFinalArgs {
        final Writer out;
        final SearchHelper shelp;
        final RuntimeEnvironment env;
        final String xrefPrefix;
        final String morePrefix;
        final int tabSize;

        public PrintPlainFinalArgs(Writer out, SearchHelper shelp,
                RuntimeEnvironment env, String xrefPrefix, int tabSize,
                String morePrefix) {
            this.out = out;
            this.shelp = shelp;
            this.env = env;
            this.xrefPrefix = xrefPrefix;
            this.morePrefix = morePrefix;
            this.tabSize = tabSize;
        }
    }
}
