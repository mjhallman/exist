/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2013 The eXist Project
 *  http://exist-db.org
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 *  $Id$
 */
package org.exist.xquery.modules.lucene;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.xml.transform.OutputKeys;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.facet.params.FacetSearchParams;
import org.apache.lucene.facet.search.CountFacetRequest;
import org.apache.lucene.facet.search.FacetRequest;
import org.apache.lucene.facet.search.FacetResult;
import org.apache.lucene.facet.search.FacetResultNode;
import org.apache.lucene.facet.taxonomy.CategoryPath;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.exist.Database;
import org.exist.dom.DefaultDocumentSet;
import org.exist.dom.DocumentImpl;
import org.exist.dom.MutableDocumentSet;
import org.exist.dom.NodeProxy;
import org.exist.dom.QName;
import org.exist.indexing.IndexController;
import org.exist.indexing.lucene.LuceneIndex;
import org.exist.indexing.lucene.LuceneIndexWorker;
import org.exist.indexing.lucene.LuceneMatchChunkListener;
import org.exist.indexing.lucene.LuceneUtil;
import org.exist.indexing.lucene.PlainTextHighlighter;
import org.exist.indexing.lucene.QueryDocuments;
import org.exist.indexing.lucene.QueryNodes;
import org.exist.indexing.lucene.SearchCallback;
import org.exist.memtree.MemTreeBuilder;
import org.exist.memtree.NodeImpl;
import org.exist.storage.DBBroker;
import org.exist.storage.serializers.Serializer;
import org.exist.util.serializer.SAXSerializer;
import org.exist.util.serializer.SerializerPool;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.Dependency;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.NodeValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.Type;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Class implementing the ft:search-facet() method
 * 
 * @author <a href="mailto:shabanovd@gmail.com">Dmitriy Shabanov</a>
 */
public class FacetSearch extends BasicFunction {

    private static final Logger logger = Logger.getLogger(FacetSearch.class);
    
    private static final QName SEARCH = new QName("facet-search", LuceneModule.NAMESPACE_URI, LuceneModule.PREFIX);
    
    private static final String DESCRIPTION = "Faceted search for data with lucene";
    
    private static final FunctionParameterSequenceType QUERY = 
    		new FunctionParameterSequenceType("query", Type.STRING, Cardinality.EXACTLY_ONE, "query string");
    
    private static final FunctionParameterSequenceType MAXHITS = 
    		new FunctionParameterSequenceType("max-hits", Type.INTEGER, Cardinality.EXACTLY_ONE, "max hits");

    private static final FunctionParameterSequenceType FACET = 
    		new FunctionParameterSequenceType("facet-request", Type.NODE, Cardinality.ONE_OR_MORE, "facet request");

    private static final FunctionParameterSequenceType SORT = 
    		new FunctionParameterSequenceType("sort-criteria", Type.NODE, Cardinality.ONE_OR_MORE, "sort criteria");

    private static final FunctionParameterSequenceType HIGHLIGHT = 
    		new FunctionParameterSequenceType("highlight", Type.BOOLEAN, Cardinality.EXACTLY_ONE, "highlight matcher(s)");

    private static final FunctionReturnSequenceType RETURN = 
    		new FunctionReturnSequenceType(Type.NODE, Cardinality.EXACTLY_ONE, "All documents that are match by the query");

    /**
     * Function signatures
     */
    public final static FunctionSignature signatures[] = {
        new FunctionSignature(
    		SEARCH,
            DESCRIPTION,
            new SequenceType[]{
                new FunctionParameterSequenceType("path", Type.STRING, Cardinality.ZERO_OR_MORE,
                "URI paths of documents or collections in database. Collection URIs should end on a '/'."),
                QUERY,
                MAXHITS,
                HIGHLIGHT,
                FACET,
                SORT
            },
            RETURN
        ),
		new FunctionSignature(
			SEARCH,
			DESCRIPTION,
			new SequenceType[]{
				QUERY,
				MAXHITS,
				HIGHLIGHT,
				FACET,
				SORT
			},
			RETURN
		)
    };

    /**
     * Constructor
     */
    public FacetSearch(XQueryContext context, FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {

        NodeImpl report = null;
        try {
            // Only match documents that match these URLs 
            List<String> toBeMatchedURIs = new ArrayList<String>();

            Sequence pathSeq = getSignature() == signatures[0] ? args[0] : contextSequence;
            if (pathSeq == null)
            	return Sequence.EMPTY_SEQUENCE;
            
            // Get first agument, these are the documents / collections to search in
            for (SequenceIterator i = pathSeq.iterate(); i.hasNext();) {
            	String path;
                Item item = i.nextItem();
                if (Type.subTypeOf(item.getType(), Type.NODE)) {
                	if (((NodeValue)item).isPersistentSet()) {
                		path = ((NodeProxy)item).getDocument().getURI().toString();
                	} else {
                		path = item.getStringValue();
                	}
                } else {
                	path = item.getStringValue();
                }
                toBeMatchedURIs.add(path);
            }

            // Get second argument, this is the query
            String query = args[getSignature() == signatures[0] ? 1 : 0].itemAt(0).getStringValue();
            
            int maxHits = args[getSignature() == signatures[0] ? 2 : 1].toJavaObject(int.class);

            boolean highlight = args[getSignature() == signatures[0] ? 3 : 2].effectiveBooleanValue();

            FacetSearchParams facetRequests = parseFacetRequests(args[getSignature() == signatures[0] ? 4 : 3]);
            Sort sortCriteria = parseSortCriteria(args[getSignature() == signatures[0] ? 5 : 4]);

            // Perform search
            report = search(toBeMatchedURIs, query, maxHits, highlight, facetRequests, sortCriteria);


        } catch (XPathException ex) {
            // Log and rethrow
            logger.error(ex);
            throw ex;
        }

        // Return list of matching files.
        return report;
    }
    
    public int getDependencies() {
    	return Dependency.CONTEXT_SET;
    }
    
    private NodeImpl search(final List<String> toBeMatchedURIs, String queryText, int maxHits, boolean highlight, FacetSearchParams facetRequests, Sort sortCriteria) throws XPathException {
        
    	final DBBroker broker = context.getBroker();
    	
    	final IndexController indexController = broker.getIndexController();
    	
        // Get the lucene worker
        final LuceneIndexWorker indexWorker = 
        		(LuceneIndexWorker) indexController.getWorkerByIndexId(LuceneIndex.ID);
        
        final LuceneIndex index = indexWorker.index;

        NodeImpl report = null;
        
        IndexSearcher searcher = null;
        try {
            // Get index searcher
            searcher = index.getSearcher();
            
            // Get analyzer : to be retrieved from configuration
            final Analyzer searchAnalyzer = new StandardAnalyzer(LuceneIndex.LUCENE_VERSION_IN_USE);

            final Set<String> fieldsToLoad = new HashSet<String>();
            
            final QueryParser parser;
            if (queryText.startsWith("ALL:")) {
            	
            	Database db = index.getBrokerPool();
            	
            	List<QName> qnames = indexWorker.getDefinedIndexes(null);
            	
            	String[] names = new String[qnames.size()];
            	
            	int i = 0;
            	for (QName qname : qnames) {
            		final String field = LuceneUtil.encodeQName(qname, db.getSymbols());
            		
            		names[i++] = field;
            		fieldsToLoad.add(field);
            	}
            	
            	parser = new MultiFieldQueryParser(LuceneIndex.LUCENE_VERSION_IN_USE, names, searchAnalyzer);
            
            	queryText = queryText.substring(4);
            } else {
                // Setup query Version, default field, analyzer
                parser = new QueryParser(LuceneIndex.LUCENE_VERSION_IN_USE, "", searchAnalyzer);
            }
            
            final Query query = parser.parse(queryText);
                       
            final MemTreeBuilder builder = new MemTreeBuilder();
            builder.startDocument();
            
            // start root element
            final int nodeNr = builder.startElement("", "results", "results", null);
            
            builder.namespaceNode("exist", "http://exist.sourceforge.net/NS/exist");
            
            MutableDocumentSet docs = new DefaultDocumentSet(1031);
            for (String uri : toBeMatchedURIs) {
            	broker.getCollection(XmldbURI.xmldbUriFor(uri)).allDocs(broker, docs, true);
            }
            
            QName bq = null;
            
            List<FacetResult> results = null;
            if (highlight) {
            	
            	final String[] fields;
            	if (fieldsToLoad.isEmpty()) {
            		// extract all used fields from query
            		fields = LuceneUtil.extractFields(query, searcher.getIndexReader());
            	} else {
            		
            		for (String field : LuceneUtil.extractFields(query, searcher.getIndexReader())) {
            			fieldsToLoad.add(field);
            		}
            		
            		fields = fieldsToLoad.toArray(new String[fieldsToLoad.size()]);
            	}
                
	            SearchCallback<NodeProxy> cb = new SearchCallback<NodeProxy>() {
	            	
	            	int total = -1;
	
					@Override
					public void totalHits(Integer number) {
						total = number;
					}
	
					@Override
					public void found(AtomicReader reader, int docNum, NodeProxy element, float score) {
//						try {
//							System.out.println("");
//							System.out.println( queryResult2String(broker, 10, element) );
//						} catch (Throwable e) {
//							e.printStackTrace();
//						}
						
						String fDocUri = element.getDocument().getURI().toString();
						
	                    // setup attributes
	                    AttributesImpl attribs = new AttributesImpl();
	                    attribs.addAttribute("", "uri", "uri", "CDATA", fDocUri);
	                    attribs.addAttribute("", "score", "score", "CDATA", ""+score);
	
	                    // write element and attributes
	                    builder.startElement("", "search", "search", attribs);
	                    	builder.addReferenceNode(element);
	                    builder.endElement();
	
	                    // clean attributes
	                    //attribs.clear();
					}
				};
				
	            // Perform actual search
	            results = QueryNodes.query(indexWorker, bq, getContextId(), docs, query, facetRequests, cb, maxHits, sortCriteria);
            } else {
	            SearchCallback<DocumentImpl> cb = new SearchCallback<DocumentImpl>() {
	            	
	            	int total = -1;
	
					@Override
					public void totalHits(Integer number) {
						total = number;
					}
	
					@Override
					public void found(AtomicReader reader, int docNum, DocumentImpl document, float score) {

						String fDocUri = document.getURI().toString();
						
	                    // setup attributes
	                    AttributesImpl attribs = new AttributesImpl();
	                    attribs.addAttribute("", "uri", "uri", "CDATA", fDocUri);
	                    attribs.addAttribute("", "score", "score", "CDATA", ""+score);
	
	                    // write element and attributes
	                    builder.startElement("", "search", "search", attribs);
	                    builder.endElement();
	
	                    // clean attributes
	                    attribs.clear();
					}
				};
				
	            // Perform actual search
	            results = QueryDocuments.query(indexWorker, docs, query, facetRequests, cb, maxHits, sortCriteria);
            }
            
            if (results != null) {
	            AttributesImpl attribs = new AttributesImpl();
	            builder.startElement("", "facet-result", "facet-result", attribs);
	            //process facet results
	            for (FacetResult facet : results) {
	            	
	                //assertEquals(2, facet.getNumValidDescendants());
	                FacetResultNode root = facet.getFacetResultNode();
	                
	                
	                attribs.clear();
	                
	                attribs.addAttribute("", "label", "label", "CDATA", root.label.toString());
	                attribs.addAttribute("", "value", "value", "CDATA", String.valueOf(root.value));
	                
	                builder.startElement("", "node", "node", attribs);
	                
	                for (FacetResultNode node : root.subResults) {
	
	                    attribs.clear();
	                    
	                    attribs.addAttribute("", "label", "label", "CDATA", node.label.toString());
	                    attribs.addAttribute("", "value", "value", "CDATA", String.valueOf(node.value));
	                    
	                    builder.startElement("", "node", "node", attribs);
	
	                    builder.endElement();
	                }
	                
	                builder.endElement();
	            }
	            // finish facet element
	            builder.endElement();
            }
            
            // finish root element
            builder.endElement();
            
            //System.out.println(builder.getDocument().toString());
            
            report = ((org.exist.memtree.DocumentImpl) builder.getDocument()).getNode(nodeNr);


        } catch (Exception ex){
            //ex.printStackTrace();
            LuceneIndexWorker.LOG.error(ex);
            throw new XPathException(ex);
        
        } finally {
            index.releaseSearcher(searcher);
        }
        
        
        return report;
    }
    
    protected FacetSearchParams parseFacetRequests(Sequence optSeq) throws XPathException {

        if (optSeq.isEmpty()) 
        	throw new XPathException(this, "Facet request can't be ZERO");
        
        if (optSeq.getItemCount() > 1) 
        	throw new XPathException(this, "Facet request can't be MANY");

        List<FacetRequest> facetRequests = new ArrayList<FacetRequest>();
        
        SequenceIterator iter = optSeq.iterate();
        while (iter.hasNext()) {
        	Element element = (Element) iter.nextItem();

        	String localName = element.getLocalName();
        	if ("count".equalsIgnoreCase(localName)) {
        		
        		String str = null;
        		int num;
        		try {
        			str = element.getAttribute("num");
        			num = Integer.valueOf( str );
        		} catch (NumberFormatException e) {
                    throw new XPathException(this, "Can't convert to integer '" + str + "'.");
        		}
        	
        		facetRequests.add(new CountFacetRequest(new CategoryPath(((Item)element).getStringValue()), num));
        	} else 
                throw new XPathException(this, "Unknown facet request '" + localName + "'.");
        }

        return new FacetSearchParams(facetRequests);
    }

    protected Sort parseSortCriteria(Sequence optSeq) throws XPathException {

        if (optSeq.isEmpty()) 
        	return null;
        
        List<SortField> sortFields = new ArrayList<SortField>();
        
        SequenceIterator iter = optSeq.iterate();
        while (iter.hasNext()) {
        	Element element = (Element) iter.nextItem();

    		SortField.Type type = SortField.Type.STRING;
    		boolean reverse = false;
    		
    		String str = element.getAttribute("type");
    		if (str == null || str.isEmpty()) {
    		} else if ("STRING".equalsIgnoreCase(str)) {
        		type = SortField.Type.STRING;
    		} else {
    			throw new XPathException(this, "Uknown sort field type '"+str+"'.");
    		}
    		
    		str = element.getAttribute("reverse");
    		if (str != null && !str.isEmpty()) {
    			reverse = Boolean.valueOf(str);
    		}

    		String field = ((Item)element).getStringValue();
    		
    		if (field == null || field.isEmpty())
    			throw new XPathException(this, "Field name can't be empty or undefined.");
    		
    		sortFields.add(new SortField(field, type, reverse));
        }

        return new Sort(sortFields.toArray(new SortField[sortFields.size()]));
    }
 
    private String queryResult2String(DBBroker broker, int chunkOffset, NodeProxy proxy) throws SAXException, XPathException {
        Properties props = new Properties();
        props.setProperty(OutputKeys.INDENT, "no");
        
        Serializer serializer = broker.getSerializer();
        serializer.reset();
        
        LuceneMatchChunkListener highlighter = new LuceneMatchChunkListener(getLuceneIndex(broker), chunkOffset);
        highlighter.reset(broker, proxy);
        
        final StringWriter writer = new StringWriter();
        
        SerializerPool serializerPool = SerializerPool.getInstance();
        SAXSerializer xmlout = (SAXSerializer) serializerPool.borrowObject(SAXSerializer.class);
        try {
        	//setup pipes
			xmlout.setOutput(writer, props);
			
			highlighter.setNextInChain(xmlout);
			
			serializer.setReceiver(highlighter);
			
			//serialize
	        serializer.toReceiver(proxy, false, true);
	        
	        //get result
	        return writer.toString();
        } finally {
        	serializerPool.returnObject(xmlout);
        }
    }
    
    private LuceneIndex getLuceneIndex(DBBroker broker) {
        return (LuceneIndex) broker.getDatabase().getIndexManager().getIndexById(LuceneIndex.ID);
    }
}
