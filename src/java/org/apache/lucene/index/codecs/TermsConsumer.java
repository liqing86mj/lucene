package org.apache.lucene.index.codecs;

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

/**
 * NOTE: this API is experimental and will likely change
 */

public abstract class TermsConsumer {

  // nocommit -- CharSequence?
  /** Starts a new term in this field; term ends with U+FFFF
   *  char */
  public abstract DocsConsumer startTerm(char[] text, int start) throws IOException;

  /** Finishes the current term */
  public abstract void finishTerm(char[] text, int start, int numDocs) throws IOException;

  /** Called when we are done adding terms to this field */
  public abstract void finish() throws IOException;
}