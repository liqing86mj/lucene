package org.apache.lucene.index.codecs.standard;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.apache.lucene.index.DocsEnum;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.FieldsEnum;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.TermRef;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.codecs.Codec;
import org.apache.lucene.index.codecs.FieldsProducer;
import org.apache.lucene.index.codecs.pulsing.PulsingDocsWriter.Document;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.CloseableThreadLocal;

/** Handles a terms dict, but defers all details of postings
 *  reading to an instance of {@TermsDictDocsReader}. This
 *  terms dict codec is meant to be shared between
 *  different postings codecs, but, it's certainly possible
 *  to make a codec that has its own terms dict writer/reader. */

public class StandardTermsDictReader extends FieldsProducer {
  private final IndexInput in;

  private final StandardDocsProducer docs;

  final TreeMap<String,FieldReader> fields = new TreeMap<String,FieldReader>();

  private final String segment;
  private StandardTermsIndexReader indexReader;


  public StandardTermsDictReader(StandardTermsIndexReader indexReader, Directory dir, FieldInfos fieldInfos, String segment, StandardDocsProducer docs, int readBufferSize)
    throws IOException {
    
    this.segment = segment;
    this.docs = docs;
    
    String file = IndexFileNames.segmentFileName(segment, StandardCodec.TERMS_EXTENSION);
    //nocommit
    if(!dir.fileExists(file)) {
      in = null;
      return;
    }
    in = dir.openInput(file, readBufferSize);


    boolean success = false;
    try {
      Codec.checkHeader(in, StandardTermsDictWriter.CODEC_NAME, StandardTermsDictWriter.VERSION_CURRENT);

      final long dirOffset = in.readLong();


      // Have DocsProducer init itself
      docs.start(in);

      // Read per-field details
      in.seek(dirOffset);

      final int numFields = in.readInt();

      // mxx
      if (Codec.DEBUG) {
        System.out.println(Thread.currentThread().getName() + ": stdr create seg=" + segment + " numFields=" + numFields + " hasProx?=" + fieldInfos.hasProx());
      }

      for(int i=0;i<numFields;i++) {
        final int field = in.readInt();
        final long numTerms = in.readLong();
        final long termsStartPointer = in.readLong();
        final StandardTermsIndexReader.FieldReader fieldIndexReader;
        final FieldInfo fieldInfo = fieldInfos.fieldInfo(field);
        if (Codec.DEBUG) {
          System.out.println("  stdr: load field=" + fieldInfo.name + " numTerms=" + numTerms);
        }
        if (indexReader != null) {
          fieldIndexReader = indexReader.getField(fieldInfo);
        } else {
          fieldIndexReader = null;
        }
        if (numTerms > 0) {
          assert !fields.containsKey(fieldInfo.name);
          fields.put(fieldInfo.name, new FieldReader(fieldIndexReader, fieldInfo, numTerms, termsStartPointer));
        }
      }
      success = true;
    } finally {
      if (!success) {
        in.close();
      }
    }

    this.indexReader = indexReader;
  }

  @Override
  public void loadTermsIndex() throws IOException {
    indexReader.loadTermsIndex();
  }

  @Override
  public void close() throws IOException {
    try {
      try {
        if(indexReader != null) {
          indexReader.close();
        }
      } finally {
        if(in != null) {
          in.close();
        }
      }
    } finally {
      try {
        if(docs != null) {
          docs.close();
        }
      } finally {
        for(FieldReader field : fields.values()) {
          field.close();
        }
      }
    }
  }

  public static void files(Directory dir, SegmentInfo segmentInfo, Collection<String> files) {
    files.add(IndexFileNames.segmentFileName(segmentInfo.name, StandardCodec.TERMS_EXTENSION));
  }

  public static void getExtensions(Collection<String> extensions) {
    extensions.add(StandardCodec.TERMS_EXTENSION);
  }

  @Override
  public FieldsEnum iterator() {
    return new TermFieldsEnum();
  }

  @Override
  public Terms terms(String field) throws IOException {
    if (Codec.DEBUG) {
      System.out.println("stdr.terms field=" + field + " found=" + fields.get(field));
    }
    return fields.get(field);
  }

  // Iterates through all known fields
  private class TermFieldsEnum extends FieldsEnum {
    final Iterator<FieldReader> it;
    FieldReader current;

    TermFieldsEnum() {
      it = fields.values().iterator();
    }

    @Override
    public String next() {
      if (Codec.DEBUG) {
        System.out.println("stdr.tfe.next seg=" + segment);
        //new Throwable().printStackTrace(System.out);
      }
      if (it.hasNext()) {
        current = it.next();
        if (Codec.DEBUG) {
          System.out.println("  hasNext set current field=" + current.fieldInfo.name);
        }
        return current.fieldInfo.name;
      } else {
        current = null;
        return null;
      }
    }
    
    @Override
    public TermsEnum terms() throws IOException {
      return current.iterator();
    }
  }
  
  private class FieldReader extends Terms {
    private final CloseableThreadLocal<ThreadResources> threadResources = new CloseableThreadLocal<ThreadResources>();
    final long numTerms;
    final FieldInfo fieldInfo;
    final long termsStartPointer;
    final StandardTermsIndexReader.FieldReader indexReader;

    FieldReader(StandardTermsIndexReader.FieldReader fieldIndexReader, FieldInfo fieldInfo, long numTerms, long termsStartPointer) {
      assert numTerms > 0;
      this.fieldInfo = fieldInfo;
      this.numTerms = numTerms;
      this.termsStartPointer = termsStartPointer;
      this.indexReader = fieldIndexReader;
    }

    @Override
    public int docFreq(TermRef text) throws IOException {
      ThreadResources resources = getThreadResources();
      if (resources.termsEnum.seek(text) == TermsEnum.SeekStatus.FOUND) {
        return resources.termsEnum.docFreq();
      } else {
        return 0;
      }
    }

    public void close() {
      threadResources.close();
    }
    
    protected ThreadResources getThreadResources() throws IOException {
      ThreadResources resources = (ThreadResources) threadResources.get();
      if (resources == null) {
        // Cache does not have to be thread-safe, it is only used by one thread at the same time
        resources = new ThreadResources(new SegmentTermsEnum(), numTerms);
        threadResources.set(resources);
      }
      return resources;
    }
    
    @Override
    public TermsEnum iterator() throws IOException {
      return new SegmentTermsEnum();
    }

    @Override
    public long getUniqueTermCount() {
      return numTerms;
    }

    // Iterates through terms in this field
    private class SegmentTermsEnum extends TermsEnum {
      private final IndexInput in;
      private final DeltaBytesReader bytesReader;
      // nocommit: long?
      private int termUpto;
      private final StandardDocsProducer.Reader docs;
      private int docFreq;
      private final StandardTermsIndexReader.TermsIndexResult indexResult = new StandardTermsIndexReader.TermsIndexResult();
      
      SegmentTermsEnum() throws IOException {
        if (Codec.DEBUG) {
          System.out.println("tdr " + this + ": CREATE TermsEnum field=" + fieldInfo.name + " startPos=" + termsStartPointer + " seg=" + segment);
        }
        in = (IndexInput) StandardTermsDictReader.this.in.clone();
        in.seek(termsStartPointer);
        bytesReader = new DeltaBytesReader(in);
        if (Codec.DEBUG) {
          System.out.println("  bytesReader=" + bytesReader);
        }
        docs = StandardTermsDictReader.this.docs.reader(fieldInfo, in);
      }

      /** Seeks until the first term that's >= the provided
       *  text; returns SeekStatus.FOUND if the exact term
       *  is found, SeekStatus.NOT_FOUND if a different term
       *  was found, SeekStatus.END if we hit EOF */
      @Override
      public SeekStatus seek(TermRef term) throws IOException {
        ReuseLRUCache<TermRef, CacheEntry> cache = null;
        CacheEntry entry = null;

        if (docs.canCaptureState()) {
          final ThreadResources resources = getThreadResources();
          cache = resources.cache;

          entry = cache.get(term);
          if (entry != null) {
            docFreq = entry.freq;
            bytesReader.term.copy(entry.term);
            docs.setState(entry, docFreq);
            termUpto = entry.termUpTo;
            // nocommit -- would be better to do this lazy?
            in.seek(entry.filePointer);
            return SeekStatus.FOUND;
          }
        }
        
        // mxx
        if (Codec.DEBUG) {
          System.out.println(Thread.currentThread().getName() + ":stdr.seek(text=" + fieldInfo.name + ":" + term + ") seg=" + segment);
        }

        if (bytesReader.started && termUpto < numTerms && bytesReader.term.compareTerm(term) == 0) {
          // nocommit -- not right if text is ""?
          // mxx
          if (Codec.DEBUG) {
            System.out.println(Thread.currentThread().getName() + ":  already here!");
          }
          // nocommit -- cache this
          return SeekStatus.FOUND;
        }

        // Find latest index term that's <= our text:
        indexReader.getIndexOffset(term, indexResult);

        // mxx
        if (Codec.DEBUG) {
          System.out.println(Thread.currentThread().getName() + ":  index pos=" + indexResult.position + " termFP=" + indexResult.offset + " term=" + indexResult.term + " this=" + this);
        }

        in.seek(indexResult.offset);

        // NOTE: the first next() after an index seek is
        // wasteful, since it redundantly reads the same
        // bytes into the buffer
        bytesReader.reset(indexResult.term);

        termUpto = indexResult.position;
        assert termUpto>=0: "termUpto=" + termUpto;

        // mxx
        if (Codec.DEBUG) {
          System.out.println(Thread.currentThread().getName() + ":  set termUpto=" + termUpto);
        }

        // Now, scan:

        //int indexCount = 0;
        //int lastIndexCount = 0;
        int scanCnt = 0;
        while(next() != null) {
          scanCnt++;
          final int cmp = bytesReader.term.compareTerm(term);
          if (cmp == 0) {
            // mxx
            if (Codec.DEBUG) {
              System.out.println(Thread.currentThread().getName() + ":  seek done found term=" + bytesReader.term);
              //new Throwable().printStackTrace(System.out);
            }

            if (docs.canCaptureState()) {
              // Store in cache
              if (cache.eldest != null) {
                entry = cache.eldest;
                cache.eldest = null;
                docs.captureState(entry);
                entry.term.copy(bytesReader.term);
              } else {
                entry = docs.captureState(null);
                entry.term = (TermRef) bytesReader.term.clone();
              }
              entry.freq = docFreq;
              entry.termUpTo = termUpto;
              entry.filePointer = in.getFilePointer();
            
              cache.put(entry.term, entry);
            }
            return SeekStatus.FOUND;
          } else if (cmp > 0) {
            // mxx
            if (Codec.DEBUG) {
              System.out.println(Thread.currentThread().getName() + ":  seek done did not find term=" + term + " found instead: " + bytesReader.term);
            }
            return SeekStatus.NOT_FOUND;
          }

          // We should not cross another indexed term while
          // scanning:

          // nocommit -- not correct that we call
          // isIndexTerm, twice
          //indexCount += indexReader.isIndexTerm(termUpto, docFreq) ? 1:0;
          //assert lastIndexCount < indexDivisor: " indexCount=" + lastIndexCount + " indexDivisor=" + indexDivisor;
          //lastIndexCount = indexCount;

          // mxx
          //System.out.println(Thread.currentThread().getName() + ":  cycle");
        }

        // mxx
        if (Codec.DEBUG) {
          System.out.println(Thread.currentThread().getName() + ": seek done did not find term=" + term + ": hit EOF");
        }
        return SeekStatus.END;
      }

      @Override
      public SeekStatus seek(long pos) throws IOException {
        if (pos >= numTerms) {
          return SeekStatus.END;
        }
        indexReader.getIndexOffset(pos, indexResult);
        in.seek(indexResult.offset);

        // NOTE: the first next() after an index seek is
        // wasteful, since it redundantly reads the same
        // bytes into the buffer
        bytesReader.reset(indexResult.term);

        termUpto = indexResult.position;
        assert termUpto>=0: "termUpto=" + termUpto;

        // Now, scan:
        int left = (int) (1 + pos - termUpto);
        while(left > 0) {
          TermRef term = next();
          assert term != null;
          left--;
        }

        // always found
        return SeekStatus.FOUND;
      }

      @Override
      public TermRef term() {
        return bytesReader.term;
      }

      @Override
      public long ord() {
        return termUpto;
      }

      @Override
      public TermRef next() throws IOException {
        if (termUpto >= numTerms) {
          return null;
        }
        if (Codec.DEBUG) {
          System.out.println("tdr.next: field=" + fieldInfo.name + " termsInPointer=" + in.getFilePointer() + " vs len=" + in.length() + " seg=" + segment);
          //new Throwable().printStackTrace(System.out);
        }
        bytesReader.read();
        docFreq = in.readVInt();
        if (Codec.DEBUG) {
          System.out.println("  text=" + bytesReader.term + " freq=" + docFreq);
        }
        // TODO: would be cleaner, but space-wasting, to
        // simply record a bit into each index entry as to
        // whether it's an index entry or not... or,
        // possibly store a "how many terms until next index
        // entry" in each index entry, but that'd require
        // some tricky lookahead work when writing the index
        final boolean isIndex = indexReader.isIndexTerm(termUpto, docFreq);

        // mxx
        // System.out.println(Thread.currentThread().getName() + ": isIndex=" + isIndex);

        docs.readTerm(docFreq, isIndex);
        termUpto++;
        if (Codec.DEBUG) {
          System.out.println("  termUpto=" + termUpto + " vs numTerms=" + numTerms + " fp=" + in.getFilePointer());
        }
        return bytesReader.term;
      }

      @Override
      public int docFreq() {
        return docFreq;
      }

      @Override
      public DocsEnum docs(Bits skipDocs) throws IOException {
        // nocommit
        if (Codec.DEBUG) {
          System.out.println("stdr.docs");
        }
        DocsEnum docsEnum = docs.docs(skipDocs);
        if (Codec.DEBUG) {
          docsEnum.desc = fieldInfo.name + ":" + bytesReader.term;
        }
        return docsEnum;
      }
    }
  }

  // nocommit -- scrutinize API
  public static class CacheEntry {
    int termUpTo;
    TermRef term;
    long filePointer;
    // nocommit
    public int freq;
    public Document docs[];
    public boolean pendingIndexTerm;

  }

  private static final int MAX_CACHE_SIZE = 1024;
  
  /**
   * Per-thread resources managed by ThreadLocal
   */
  private static final class ThreadResources {
    // Used for caching the least recently looked-up Terms
    final ReuseLRUCache<TermRef, CacheEntry> cache;
    final TermsEnum termsEnum;

    ThreadResources(TermsEnum termsEnum, long numTerms) {
      final int cacheSize;
      if (numTerms >= MAX_CACHE_SIZE) {
        cacheSize = MAX_CACHE_SIZE;
      } else if (numTerms < 1) {
        cacheSize = 1;
      } else {
        cacheSize = (int) numTerms;
      }

      cache = new ReuseLRUCache<TermRef, CacheEntry>(cacheSize);
      this.termsEnum = termsEnum;
    }
  }

  // TODO: -- wonder if simple double-barrel LRU cache
  // would be better
  private static class ReuseLRUCache<K,V> extends LinkedHashMap<K, V> {

    /**
     * 
     */
    private static final long serialVersionUID = 1L;
    private final static float LOADFACTOR = 0.75f;
    private int cacheSize;
    V eldest;

    /**
     * Creates a last-recently-used cache with the specified size.
     */
    public ReuseLRUCache(int cacheSize) {
      // TODO: -- we should not init cache w/ full
      // capacity? init it at 0, and only start evicting
      // once #entries is over our max
      super((int) Math.ceil(cacheSize / LOADFACTOR) + 1, LOADFACTOR, true);
      this.cacheSize = cacheSize;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
      boolean remove = size() > ReuseLRUCache.this.cacheSize;
      if (remove) {
        this.eldest = eldest.getValue();
      }
      return remove;
    }
  }

}