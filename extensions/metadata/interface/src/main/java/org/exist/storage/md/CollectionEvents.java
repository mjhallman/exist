/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2012-2013 The eXist Project
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
package org.exist.storage.md;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.exist.Database;
import org.exist.collections.Collection;
import org.exist.collections.triggers.CollectionTrigger;
import org.exist.collections.triggers.TriggerException;
import org.exist.dom.DocumentImpl;
import org.exist.indexing.IndexWorker;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.DBBroker;
import org.exist.storage.lock.Lock;
import org.exist.storage.txn.Txn;
import org.exist.xmldb.XmldbURI;

/**
 * @author <a href="mailto:shabanovd@gmail.com">Dmitriy Shabanov</a>
 *
 */
public class CollectionEvents implements CollectionTrigger {

	private Database db;
	
	@Override
	public void configure(DBBroker broker, Collection parent, Map<String, List<? extends Object>> parameters) throws TriggerException {
		db = broker.getDatabase();
	}

	@Override
	public void beforeCreateCollection(DBBroker broker, Txn txn, XmldbURI uri) throws TriggerException {
//		System.out.println("beforeCreateCollection "+uri);
	}

	@Override
	public void afterCreateCollection(DBBroker broker, Txn txn, Collection collection) {
//		System.out.println("afterCreateCollection "+collection.getURI());
		try {
			MDStorageManager._.md.addMetas(collection);
		} catch (Throwable e) {
			MDStorageManager.LOG.fatal(e);
		}
		
		index(collection);
	}

	@Override
	public void beforeCopyCollection(DBBroker broker, Txn txn, Collection collection, XmldbURI newUri) throws TriggerException {
	}

	@Override
	public void afterCopyCollection(DBBroker broker, Txn txn, Collection collection, XmldbURI oldUri) {
//		System.out.println("afterCopyCollection "+collection.getURI());
		try {
			MDStorageManager._.md.copyMetas(oldUri, collection);
		} catch (Throwable e) {
			MDStorageManager.LOG.fatal(e);
		}
		
		index(collection);
	}

	@Override
	public void beforeMoveCollection(DBBroker broker, Txn txn, Collection collection, XmldbURI newUri) throws TriggerException {
//		System.out.println("beforeMoveCollection "+collection.getURI());
		try {
	        for(Iterator<DocumentImpl> i = collection.iteratorNoLock(broker); i.hasNext(); ) {
	            DocumentImpl doc = i.next();
	            try {
		            MDStorageManager._.md.moveMetas(
	            		collection.getURI().append(doc.getFileURI()), 
	            		newUri.append(doc.getFileURI())
	        		);
	    		} catch (Throwable e) {
	    			MDStorageManager.LOG.fatal(e);
	    		}
	        }
		} catch (PermissionDeniedException e) {
			throw new TriggerException(e);
		}
	}

	@Override
	public void afterMoveCollection(DBBroker broker, Txn txn, Collection collection, XmldbURI oldUri) {
//		System.out.println("afterMoveCollection "+oldUri+" to "+collection.getURI());
		try {
			MDStorageManager._.md.moveMetas(oldUri, collection.getURI());
		} catch (Throwable e) {
			MDStorageManager.LOG.fatal(e);
		}
		
//		removeIndex(oldUri);
		index(collection);
	}
	
	private void deleteCollectionRecursive(DBBroker broker, Collection collection) throws PermissionDeniedException {
        for(Iterator<DocumentImpl> i = collection.iterator(broker); i.hasNext(); ) {
            DocumentImpl doc = i.next();
            try {
            	MDStorageManager._.md.delMetas(doc.getURI());
			} catch (Throwable e) {
				MDStorageManager.LOG.fatal(e);
			}
        }

		final XmldbURI uri = collection.getURI();

		for(Iterator<XmldbURI> i = collection.collectionIterator(broker); i.hasNext(); ) {
            final XmldbURI childName = i.next();
            //TODO : resolve URIs !!! name.resolve(childName)
            final Collection child = broker.openCollection(uri.append(childName), Lock.NO_LOCK);
            if(child == null) {
//                LOG.warn("Child collection " + childName + " not found");
            } else {
                try {
                	deleteCollectionRecursive(broker, child);
                } finally {
                    child.release(Lock.NO_LOCK);
                }
            }
        }
//		removeIndex(collection.getURI());
	}

	@Override
	public void beforeDeleteCollection(DBBroker broker, Txn txn, Collection collection) throws TriggerException {
//		System.out.println("beforeDeleteCollection "+collection.getURI());
        try {
            for(Iterator<DocumentImpl> i = collection.iteratorNoLock(broker); i.hasNext(); ) {
                DocumentImpl doc = i.next();
                try {
                    MDStorageManager._.md.delMetas(doc.getURI());
                } catch (Throwable e) {
                    MDStorageManager.LOG.fatal(e);
                }
            }
        } catch (PermissionDeniedException e) {
            throw new TriggerException(e);
        }
	    
//		try {
//			deleteCollectionRecursive(broker, collection);
//		} catch (PermissionDeniedException e) {
//			throw new TriggerException(e);
//		}
	}

	@Override
	public void afterDeleteCollection(DBBroker broker, Txn txn, XmldbURI uri) {
//		System.out.println("afterDeleteCollection "+uri);
		try {
			MDStorageManager._.md.delMetas(uri);
		} catch (Throwable e) {
			MDStorageManager.LOG.fatal(e);
		}
	}
	
	private void index(Collection col) {
		try {
			if (db != null) {
				IndexWorker worker = 
						db.getActiveBroker()
						.getIndexController()
						.getWorkerByIndexId("org.exist.indexing.lucene.LuceneIndex");
				worker.indexCollection(col);
			}
		} catch (Throwable e) {
			MDStorageManager.LOG.fatal(e);
		}
	}
//
//	private void removeIndex(XmldbURI url) {
//		try {
//			IndexWorker worker = db.getActiveBroker().getIndexController().getWorkerByIndexId("org.exist.indexing.lucene.LuceneIndex");
//			worker.removeIndex(url);
//		} catch (Throwable e) {
//			MDStorageManager.LOG.fatal(e);
//		}
//	}
}
