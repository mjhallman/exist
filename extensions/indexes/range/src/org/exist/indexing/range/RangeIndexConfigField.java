package org.exist.indexing.range;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.collation.CollationKeyAnalyzer;
import org.apache.lucene.util.Version;
import org.exist.storage.NodePath;
import org.exist.util.Collations;
import org.exist.util.DatabaseConfigurationException;
import org.exist.xquery.XPathException;
import org.exist.xquery.value.Type;
import org.w3c.dom.Element;

import java.util.Map;

public class RangeIndexConfigField {

    private String name;
    private NodePath path = null;
    private int type = Type.STRING;
    private Analyzer analyzer = null;

    public RangeIndexConfigField(NodePath parentPath, Element elem, Map<String, String> namespaces) throws DatabaseConfigurationException {
        name = elem.getAttribute("name");
        if (name == null || name.length() == 0) {
            throw new DatabaseConfigurationException("Range index module: field element requires a name attribute");
        }
        String match = elem.getAttribute("match");
        if (match != null) {
            try {
                NodePath relPath = new NodePath(namespaces, match);
                if (relPath.length() == 0)
                    throw new DatabaseConfigurationException("Range index module: Invalid match path in collection config: " + match);
                path = new NodePath(parentPath);
                path.append(relPath);
            } catch (IllegalArgumentException e) {
                throw new DatabaseConfigurationException("Range index module: invalid qname in configuration: " + e.getMessage());
            }
        }
        String typeStr = elem.getAttribute("type");
        if (typeStr != null && typeStr.length() > 0) {
            try {
                this.type = Type.getType(typeStr);
            } catch (XPathException e) {
                throw new DatabaseConfigurationException("Invalid type declared for range index on " + match + ": " + typeStr);
            }
        }
        String collation = elem.getAttribute("collation");
        if (collation != null && collation.length() > 0) {
            try {
                analyzer = new CollationKeyAnalyzer(Version.LUCENE_43, Collations.getCollationFromURI(null, collation));
            } catch (XPathException e) {
                throw new DatabaseConfigurationException(e.getMessage(), e);
            }
        }
    }

    public String getName() {
        return name;
    }

    public NodePath getPath() {
        return path;
    }

    public int getType() {
        return type;
    }

    public Analyzer getAnalyzer() {
        return analyzer;
    }

    public boolean match(NodePath other) {
        return path.match(other);
    }
}