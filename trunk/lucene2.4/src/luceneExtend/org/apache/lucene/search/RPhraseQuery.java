package org.apache.lucene.search;

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
import java.util.Set;
import java.util.ArrayList;

import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermPositions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.model.WeightModelManager;
import org.apache.lucene.search.model.WeightingModel;
import org.apache.lucene.util.ToStringUtils;
import org.dutir.lucene.util.ApplicationSetup;

/** A Query that matches documents containing a particular sequence of terms.
 * A PhraseQuery is built by QueryParser for input like <code>"new york"</code>.
 * 
 * <p>This query may be combined with other terms or queries with a {@link BooleanQuery}.
 */
public class RPhraseQuery extends RQuery {
  private String field;
  private ArrayList terms = new ArrayList(4);
  private ArrayList positions = new ArrayList(4);
  private int maxPosition = 0;
  private int slop = 0;

  /** Constructs an empty phrase query. */
  public RPhraseQuery() {}

  /** Sets the number of other words permitted between words in query phrase.
    If zero, then this is an exact phrase search.  For larger values this works
    like a <code>WITHIN</code> or <code>NEAR</code> operator.

    <p>The slop is in fact an edit-distance, where the units correspond to
    moves of terms in the query phrase out of position.  For example, to switch
    the order of two words requires two moves (the first move places the words
    atop one another), so to permit re-orderings of phrases, the slop must be
    at least two.

    <p>More exact matches are scored higher than sloppier matches, thus search
    results are sorted by exactness.

    <p>The slop is zero by default, requiring exact matches.*/
  public void setSlop(int s) { slop = s; }
  /** Returns the slop.  See setSlop(). */
  public int getSlop() { return slop; }

  /**
   * Adds a term to the end of the query phrase.
   * The relative position of the term is the one immediately after the last term added.
   */
  public void add(Term term) {
    int position = 0;
    if(positions.size() > 0)
        position = ((Integer) positions.get(positions.size()-1)).intValue() + 1;

    add(term, position);
  }

  /**
   * Adds a term to the end of the query phrase.
   * The relative position of the term within the phrase is specified explicitly.
   * This allows e.g. phrases with more than one term at the same position
   * or phrases with gaps (e.g. in connection with stopwords).
   * 
   * @param term
   * @param position
   */
  public void add(Term term, int position) {
      if (terms.size() == 0)
          field = term.field();
      else if (term.field() != field)
          throw new IllegalArgumentException("All phrase terms must be in the same field: " + term);

      terms.add(term);
      positions.add(new Integer(position));
      if (position > maxPosition) maxPosition = position;
  }

  /** Returns the set of terms in this phrase. */
  public Term[] getTerms() {
    return (Term[])terms.toArray(new Term[0]);
  }

  /**
   * Returns the relative positions of terms in this phrase.
   */
  public int[] getPositions() {
      int[] result = new int[positions.size()];
      for(int i = 0; i < positions.size(); i++)
          result[i] = ((Integer) positions.get(i)).intValue();
      return result;
  }
  public WeightingModel weightmodel;
  
  private class RPhraseWeight implements Weight {
    private Similarity similarity;
    private float value =1;
    private float idf;
    private float queryNorm;
    private float queryWeight;
   
//    public RPhraseWeight(Searcher searcher)
//      throws IOException {
//      this.similarity = getSimilarity(searcher);
//      idf = similarity.idf(terms, searcher);
//    }
    public RPhraseWeight(Searcher searcher){
    	this.similarity = getSimilarity(searcher);
    }

    public String toString() { return "weight(" + RPhraseQuery.this + ")"; }

    public Query getQuery() { return RPhraseQuery.this; }
    public float getValue() { return getBoost(); }

    public float sumOfSquaredWeights() {
      queryWeight = idf * getBoost();             // compute query weight
      return queryWeight * queryWeight;           // square it
    }

    public void normalize(float queryNorm) {
//      this.queryNorm = queryNorm;
//      queryWeight *= queryNorm;                   // normalize query weight
//      value = queryWeight * idf;       
    	// idf for document
    	this.queryNorm = value = 1;
    }

    public RScorer scorer(IndexReader reader) throws IOException {
      if (terms.size() == 0)			  // optimize zero-term case
        return null;

      TermPositions[] tps = new TermPositions[terms.size()];
      for (int i = 0; i < terms.size(); i++) {
        TermPositions p = reader.termPositions((Term)terms.get(i));
        if (p == null)
          return null;
        tps[i] = p;
      }

      RPhraseScorer scorer = null; 
      
      String pModel = ApplicationSetup.getProperty("proximity.model", "DFR");
      if(pModel.equalsIgnoreCase("DFR")){
    	  scorer = new DFRRPhraseScorer(this, tps, getPositions(), similarity, slop, reader.norms(field));
      }else{
    	  if (slop == 0)				  // optimize exact case
          {
        	  scorer =  new RExactPhraseScorer(this, tps, getPositions(), similarity,
                      reader.norms(field));
          }
          else{
        	  scorer = new RSloppyPhraseScorer(this, tps, getPositions(), similarity, slop,
                      reader.norms(field));
          }
          
      }
      scorer.setWeightingModel(weightmodel);
      
      return scorer;

    }

    public Explanation explain(IndexReader reader, int doc)
      throws IOException {

      Explanation result = new Explanation();
      result.setDescription("weight("+getQuery()+" in "+doc+"), product of:");

      StringBuffer docFreqs = new StringBuffer();
      StringBuffer query = new StringBuffer();
      query.append('\"');
      for (int i = 0; i < terms.size(); i++) {
        if (i != 0) {
          docFreqs.append(" ");
          query.append(" ");
        }

        Term term = (Term)terms.get(i);

        docFreqs.append(term.text());
        docFreqs.append("=");
        docFreqs.append(reader.docFreq(term));

        query.append(term.text());
      }
      query.append('\"');

      Explanation idfExpl =
        new Explanation(idf, "idf(" + field + ": " + docFreqs + ")");

      // explain query weight
      Explanation queryExpl = new Explanation();
      queryExpl.setDescription("queryWeight(" + getQuery() + "), product of:");

      Explanation boostExpl = new Explanation(getBoost(), "boost");
      if (getBoost() != 1.0f)
        queryExpl.addDetail(boostExpl);
      queryExpl.addDetail(idfExpl);

      Explanation queryNormExpl = new Explanation(queryNorm,"queryNorm");
      queryExpl.addDetail(queryNormExpl);

      queryExpl.setValue(boostExpl.getValue() *
                         idfExpl.getValue() *
                         queryNormExpl.getValue());

      result.addDetail(queryExpl);

      // explain field weight
      Explanation fieldExpl = new Explanation();
      fieldExpl.setDescription("fieldWeight("+field+":"+query+" in "+doc+
                               "), product of:");

      Explanation tfExpl = scorer(reader).explain(doc);
      fieldExpl.addDetail(tfExpl);
      fieldExpl.addDetail(idfExpl);

      Explanation fieldNormExpl = new Explanation();
      byte[] fieldNorms = reader.norms(field);
      float fieldNorm =
        fieldNorms!=null ? Similarity.decodeNorm(fieldNorms[doc]) : 0.0f;
      fieldNormExpl.setValue(fieldNorm);
      fieldNormExpl.setDescription("fieldNorm(field="+field+", doc="+doc+")");
      fieldExpl.addDetail(fieldNormExpl);

      fieldExpl.setValue(tfExpl.getValue() *
                         idfExpl.getValue() *
                         fieldNormExpl.getValue());

      result.addDetail(fieldExpl);

      // combine them
      result.setValue(queryExpl.getValue() * fieldExpl.getValue());

      if (queryExpl.getValue() == 1.0f)
        return fieldExpl;

      return result;
    }
  }

  protected Weight createWeight(Searcher searcher) throws IOException {
    if (terms.size() == 1) {			  // optimize one-term case
      Term term = (Term)terms.get(0);
      RQuery termQuery = new RTermQuery(term);
      termQuery.setBoost(getBoost());
      return termQuery.createWeight(searcher);
    }
    weightmodel = WeightModelManager.getFromPropertyFile(searcher, this);
    return new RPhraseWeight(searcher);
  }

  /**
   * @see org.apache.lucene.search.Query#extractTerms(java.util.Set)
   */
  public void extractTerms(Set queryTerms) {
    queryTerms.addAll(terms);
  }

  /** Prints a user-readable version of this query. */
  public String toString(String f) {
    StringBuffer buffer = new StringBuffer();
    if (field != null && !field.equals(f)) {
      buffer.append(field);
      buffer.append(":");
    }

    buffer.append("\"");
    String[] pieces = new String[maxPosition + 1];
    for (int i = 0; i < terms.size(); i++) {
      int pos = ((Integer)positions.get(i)).intValue();
      String s = pieces[pos];
      if (s == null) {
        s = ((Term)terms.get(i)).text();
      } else {
        s = s + "|" + ((Term)terms.get(i)).text();
      }
      pieces[pos] = s;
    }
    for (int i = 0; i < pieces.length; i++) {
      if (i > 0) {
        buffer.append(' ');
      }
      String s = pieces[i];
      if (s == null) {
        buffer.append('?');
      } else {
        buffer.append(s);
      }
    }
    buffer.append("\"");

    if (slop != 0) {
      buffer.append("~");
      buffer.append(slop);
    }

    buffer.append(ToStringUtils.boost(getBoost()));

    return buffer.toString();
  }

  /** Returns true iff <code>o</code> is equal to this. */
  public boolean equals(Object o) {
    if (!(o instanceof PhraseQuery))
      return false;
    RPhraseQuery other = (RPhraseQuery)o;
    return (this.getBoost() == other.getBoost())
      && (this.slop == other.slop)
      &&  this.terms.equals(other.terms)
      && this.positions.equals(other.positions);
  }

  /** Returns a hash code value for this object.*/
  public int hashCode() {
    return Float.floatToIntBits(getBoost())
      ^ slop
      ^ terms.hashCode()
      ^ positions.hashCode();
  }

}
