/*
 * Copyright 2010-2013 10gen Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.hadoop.util;

import com.mongodb.*;
import com.mongodb.hadoop.input.MongoInputSplit;
import java.util.*;
import org.apache.commons.logging.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputSplit;
import org.bson.*;

/**
 *
 * This class is an implementation of MongoSplitter which
 * can be used on sharded collections. It gets the chunks
 * information from the cluster's config server, and produces 
 * one split for each chunk.
 *
 */
public class ShardChunkMongoSplitter extends MongoCollectionSplitter{

    private static final Log log = LogFactory.getLog( ShardChunkMongoSplitter.class );
    
    protected boolean targetShards = false;

    public ShardChunkMongoSplitter(Configuration conf, MongoURI inputURI, boolean targetShards){
        super(conf, inputURI);
        this.targetShards = targetShards;
    }

    // Generate one split per chunk.
    @Override
    public List<InputSplit> calculateSplits() throws SplitFailedException{
        this.init();
        DB configDB = this.mongo.getDB("config");
        DBCollection chunksCollection = configDB.getCollection( "chunks" );

        String inputNS = this.inputURI.getDatabase() + "." + this.inputURI.getCollection();

        DBCursor cur = chunksCollection.find(new BasicDBObject("ns", inputNS));

        int numChunks = 0;

        Map<String, String> shardsMap = null;
        if(this.targetShards){
            try{
                shardsMap = this.getShardsMap();
            }catch(Exception e){
                //Something went wrong when trying to
                //read the shards data from the config server,
                //so abort the splitting
                throw new SplitFailedException("Couldn't get shards information from config server", e);
            }
        }

        List<String> mongosHostNames = MongoConfigUtil.getInputMongosHosts(this.conf);
        if(this.targetShards && mongosHostNames.size() > 0){
            throw new SplitFailedException("Setting both mongo.input.split.read_from_shards " +
                                           "and mongo.input.mongos_hosts does not make sense. ");
        }

        ArrayList<InputSplit> returnVal = new ArrayList<InputSplit>();

        int loopIndex = 0;
        while(cur.hasNext()){
            final BasicDBObject row = (BasicDBObject)cur.next();
            BasicDBObject chunkLowerBound = (BasicDBObject)row.get("min");
            BasicDBObject chunkUpperBound = (BasicDBObject)row.get("max");
            MongoInputSplit chunkSplit = createSplitFromBounds(chunkLowerBound, chunkUpperBound);
            chunkSplit.setInputURI(inputURI);
            if(this.targetShards){
                //The job is configured to target shards, so replace the
                //mongos hostname with the host of the shard's servers
                String shard = (String)row.get("shard");
                String shardHosts = shardsMap.get(shard);
                if(shardHosts == null)
                    throw new SplitFailedException("Couldn't find shard ID: " + shard + " in config.shards.");

                MongoURI newURI = rewriteURI(inputURI, shardHosts);
                chunkSplit.setInputURI(newURI);
            }else if(mongosHostNames.size() > 0){
                //Multiple mongos hosts are specified, so
                //choose a host name in round-robin fashion
                //and rewrite the URI using that hostname.
                //This evenly distributes the load to avoid
                //pegging a single mongos instance.
                String roundRobinHost = mongosHostNames.get(numChunks % mongosHostNames.size());
                MongoURI newURI = rewriteURI(inputURI, roundRobinHost);
                chunkSplit.setInputURI(newURI);
            }
            returnVal.add(chunkSplit);
            numChunks++;
        }
        return returnVal;
    }

}
