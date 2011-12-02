/*
 * Autopsy Forensic Browser
 *
 * Copyright 2011 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
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
package org.sleuthkit.autopsy.keywordsearch;

import java.util.List;
import java.util.logging.Logger;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.sleuthkit.autopsy.keywordsearch.Server.Core;
import org.sleuthkit.datamodel.Content;

class HighlightedMatchesSource implements MarkupSource {

    private static final Logger logger = Logger.getLogger(HighlightedMatchesSource.class.getName());
    Content content;
    String solrQuery;
    Core solrCore;
    
    HighlightedMatchesSource(Content content, String solrQuery) {
        this(content, solrQuery, KeywordSearch.getServer().getCore());
    }

    HighlightedMatchesSource(Content content, String solrQuery, Core solrCore) {
        this.content = content;
        this.solrQuery = solrQuery;
        this.solrCore = solrCore;
    }
    
    

    @Override
    public String getMarkup() {

        SolrQuery q = new SolrQuery();
        q.setQuery(solrQuery);
        q.addFilterQuery("id:" + content.getId());
        q.addHighlightField("content");
        q.setHighlightSimplePre("<span style=\"background:yellow\">");
        q.setHighlightSimplePost("</span>");
        q.setHighlightFragsize(0); // don't fragment the highlight
        
        
        //TODO: remove (only for debugging)
        String queryString = q.toString();


        try {
            QueryResponse response = solrCore.query(q);
            List<String> contentHighlights = response.getHighlighting().get(Long.toString(content.getId())).get("content");
            if (contentHighlights == null) {
                return "<span style=\"background:red\">No matches in content.</span>";
            } else {
                return "<pre>" + contentHighlights.get(0).trim() + "</pre>";
            }
        } catch (SolrServerException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public String toString() {
        return "Search Matches";
    }
}
