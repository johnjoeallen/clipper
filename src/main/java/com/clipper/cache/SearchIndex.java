package com.clipper.cache;

import com.clipper.config.ClipperProperties;
import com.clipper.model.SavedPost;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-text search over saved posts, backed by a Lucene index on disk
 * (replaces the previous SQLite FTS5 virtual table).
 */
@Component
public class SearchIndex {

    private static final String[] FIELDS = {
            "title", "og_title", "selected_text", "description", "tags_text", "page_text"
    };

    private final Directory directory;
    private final Analyzer  analyzer;
    private final IndexWriter writer;

    public SearchIndex(ClipperProperties props) throws IOException {
        Path indexDir = props.getDataDir().resolve("search-index");
        Files.createDirectories(indexDir);
        this.directory = FSDirectory.open(indexDir);
        this.analyzer  = new EnglishAnalyzer();
        this.writer    = new IndexWriter(directory, new IndexWriterConfig(analyzer));
    }

    public void index(SavedPost post) {
        try {
            writer.updateDocument(new Term("post_id", post.id()), toDocument(post));
            writer.commit();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String postId) {
        try {
            writer.deleteDocuments(new Term("post_id", postId));
            writer.commit();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Rebuilds the entire index from the given posts (used on startup to stay in sync with the DB). */
    public void reindexAll(List<SavedPost> posts) {
        try {
            writer.deleteAll();
            for (SavedPost post : posts) {
                writer.addDocument(toDocument(post));
            }
            writer.commit();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns matching post IDs in relevance order. Every term must match in at least one field. */
    public List<String> search(List<String> terms) {
        if (terms.isEmpty()) return List.of();

        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            BooleanQuery.Builder outer = new BooleanQuery.Builder();
            for (String term : terms) {
                String stemmed = stem(term);
                BooleanQuery.Builder perTerm = new BooleanQuery.Builder();
                for (String field : FIELDS) {
                    perTerm.add(new PrefixQuery(new Term(field, term)), BooleanClause.Occur.SHOULD);
                    if (!stemmed.equals(term)) {
                        perTerm.add(new TermQuery(new Term(field, stemmed)), BooleanClause.Occur.SHOULD);
                    }
                }
                outer.add(perTerm.build(), BooleanClause.Occur.MUST);
            }

            TopDocs top = searcher.search(outer.build(), 500);
            StoredFields stored = searcher.storedFields();
            List<String> ids = new ArrayList<>();
            for (ScoreDoc sd : top.scoreDocs) {
                ids.add(stored.document(sd.doc).get("post_id"));
            }
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @PreDestroy
    void close() {
        try { writer.close(); } catch (IOException ignored) {}
        try { directory.close(); } catch (IOException ignored) {}
        analyzer.close();
    }

    private Document toDocument(SavedPost p) {
        Document doc = new Document();
        doc.add(new StringField("post_id", p.id(), Field.Store.YES));
        doc.add(new TextField("title", nvl(p.title()), Field.Store.NO));
        doc.add(new TextField("og_title", nvl(p.ogTitle()), Field.Store.NO));
        doc.add(new TextField("selected_text", nvl(p.selectedText()), Field.Store.NO));
        doc.add(new TextField("description", nvl(p.description()), Field.Store.NO));
        doc.add(new TextField("tags_text", String.join(" ", p.tags()), Field.Store.NO));
        doc.add(new TextField("page_text", nvl(p.pageText()), Field.Store.NO));
        return doc;
    }

    private String stem(String term) {
        try (TokenStream ts = analyzer.tokenStream("stem", term)) {
            CharTermAttribute attr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            String result = term;
            if (ts.incrementToken()) result = attr.toString();
            ts.end();
            return result;
        } catch (IOException e) {
            return term;
        }
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
