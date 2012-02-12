package com.foursquare.fongo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;

public class UpdateEngine {

  ExpressionParser expressionParser = new ExpressionParser();
  private final DBObject query;
  private final boolean debug;

  public UpdateEngine(DBObject q, boolean debug) {
    this.query = q;
    this.debug = debug;
  }

  public UpdateEngine() {
    this(new BasicDBObject(), true);
  }
  
  void keyCheck(String key, Set<String> seenKeys) {
    if (!seenKeys.add(key)){
      throw new FongoException("attempting more than one atomic update on on " + key);
    }
  }
  
  void debug(String message){
    if (debug) {
      System.out.println(message);
    }
  }
  
  abstract class BasicUpdate  {

    private final boolean createMissing;
    final String command;

    public BasicUpdate(String command, boolean createMissing) {
      this.command = command;
      this.createMissing = createMissing;
    }
     
    abstract void mergeAction(String subKey, DBObject subObject, Object object);
    
    public DBObject doUpdate(DBObject obj, DBObject update, Set<String> seenKeys){
      DBObject updateObject = (DBObject) update.get(command);
      HashSet<String> keySet = new HashSet<String>(updateObject.keySet());
      debug("KeySet is of length " + keySet.size());
      for (String updateKey : keySet) {
        debug("\tfound a key " + updateKey);
        keyCheck(updateKey, seenKeys);
        doSingleKeyUpdate(updateKey, obj, updateObject.get(updateKey));
      }
      return obj;
    }
    
    void doSingleKeyUpdate(final String updateKey, final DBObject objOriginal, Object object) {
      String[] path = ExpressionParser.DOT_PATTERN.split(updateKey);
      String subKey = path[0];
      
      DBObject obj = objOriginal;
      boolean isPositional = updateKey.contains(".$");
      if (isPositional){
        debug("got a positional for query " + query);
      }
      for (int i = 0; i < path.length - 1; i++){
        if (!obj.containsField(subKey)){
          if (createMissing && !isPositional){
            obj.put(subKey, new BasicDBObject());
          } else {
            return;
          }
        }
        Object value = obj.get(subKey);
        if ((value instanceof List) && "$".equals(path[i+1])) {
          handlePositionalUpdate(updateKey, object, (List)value, obj);
        } else if (value instanceof DBObject){
          obj = (DBObject) value;
        } else {
          throw new FongoException("subfield must be object. " + updateKey + " not in " + objOriginal);
        }
        subKey = path[i + 1];
      }
      if (!isPositional) {
        debug("Subobject is " + obj);
        mergeAction(subKey, obj, object);
        debug("Full object is " + objOriginal);
      }
    }

    public void handlePositionalUpdate(final String updateKey, Object object, List valueList, DBObject ownerObj) {
      int dollarIndex = updateKey.indexOf("$");
      String postPath = (dollarIndex == updateKey.length() -1 )  ? "" : updateKey.substring(dollarIndex + 2);
      String prePath = updateKey.substring(0, dollarIndex - 1);
      //create a filter from the original query
      Filter filter = null;
      for (String key : query.keySet()){
        if (key.startsWith(prePath)){
          String matchKey = prePath.equals(key) ? key : key.substring(prePath.length() + 1);
          filter = expressionParser.buildFilter(new BasicDBObject(matchKey, query.get(key)));
        }
      }
      if (filter == null){
        throw new FongoException("positional operator " + updateKey + " must be used on query key " + query);
      }
      
      // find the right item
      for(int i = 0; i < valueList.size(); i++){
        Object listItem = valueList.get(i);
        
        debug("found a positional list item " + listItem + " " + prePath + " " + postPath);
        if (listItem instanceof DBObject && !postPath.isEmpty()){
          
          if (filter.apply((DBObject) listItem)) {
            doSingleKeyUpdate(postPath, (DBObject) listItem, object);
            break;
          }
        } else {
          //this is kind of a waste
          if (filter.apply(new BasicDBObject(prePath, listItem))){
            BasicDBList newList = new BasicDBList();
            newList.addAll(valueList);
            ownerObj.put(prePath, newList);
            mergeAction(String.valueOf(i), newList, object);
            break;
          }
        }
      }
    }
  }
  
  Number genericAdd(Number left, Number right) { 
    if (left instanceof Float || left instanceof Double || right instanceof Float || right instanceof Double) {
      return left.doubleValue() + (right.doubleValue());
    } else if  (left instanceof Integer) {
      return left.intValue() + (right.intValue());
    } else {
      return left.longValue() + (right.intValue());
    }
  }
  
  final List<BasicUpdate> commands = Arrays.<BasicUpdate>asList(
      new BasicUpdate("$set", true) {
        @Override
        void mergeAction(String subKey, DBObject subObject, Object object) {
          subObject.put(subKey, object);
        }
      },
      new BasicUpdate("$inc", true) {
        @Override
        void mergeAction(String subKey, DBObject subObject, Object object) {
          Number updateNumber = expressionParser.typecast(command + " value", object, Number.class);
          Object oldValue = subObject.get(subKey);
          if (oldValue == null){
            subObject.put(subKey, updateNumber);
          } else {
            Number oldNumber = expressionParser.typecast(subKey + " value", oldValue, Number.class);
            subObject.put(subKey, genericAdd(oldNumber, updateNumber));
          }
        }
      },
      new BasicUpdate("$unset", false) {
        @Override
        void mergeAction(String subKey, DBObject subObject, Object object) {
          subObject.removeField(subKey);
        }
      },
      new BasicUpdate("$push", true) {
        @Override
        void mergeAction(String subKey, DBObject subObject, Object object) {
          if (!subObject.containsField(subKey)){
            subObject.put(subKey, Arrays.asList(object));
          } else {
            List currentValue = new ArrayList<Object>(expressionParser.typecast(subKey, subObject.get(subKey), List.class));
            currentValue.add(object);
            subObject.put(subKey, currentValue);
          }
        }
      },
      new BasicUpdate("$pushAll", true) {
        @Override
        void mergeAction(String subKey, DBObject subObject, Object object) {
          List newList = expressionParser.typecast(command + " value", object, List.class);
          if (!subObject.containsField(subKey)){
            subObject.put(subKey, newList);
          } else {
            List currentValue = new ArrayList<Object>(expressionParser.typecast(subKey, subObject.get(subKey), List.class));
            currentValue.addAll(newList);
            subObject.put(subKey, currentValue);
          }
        }
      },
      new BasicUpdate("$addToSet", true) {
        @Override
        void mergeAction(String subKey, DBObject subObject, Object object) {
          boolean isEach = false;
          List currentValueImmutableList = expressionParser.typecast(subKey, subObject.get(subKey), List.class);
          List currentValue = (currentValueImmutableList == null) ? new ArrayList<Object>() :
            new ArrayList<Object>(currentValueImmutableList);
          if (object instanceof DBObject){
            Object eachObject = ((DBObject)object).get("$each");
            if (eachObject != null){
              isEach = true;
              List newList = expressionParser.typecast(command + ".$each value", eachObject, List.class);
              if (newList == null){
                throw new FongoException(command + ".$each must not be null");
              }
              for (Object newValue : newList){
                if (!currentValue.contains(newValue)){
                  currentValue.add(newValue);               
                }
              }
            } 
          }
          if (!isEach) {
            if (!currentValue.contains(object)){
              currentValue.add(object);               
            }
          }
          subObject.put(subKey, currentValue);
        }
      },
      new BasicUpdate("$pop", false) {
        @Override
        void mergeAction(String subKey, DBObject subObject, Object object) {
          List currentList = expressionParser.typecast(command, subObject.get(subKey), List.class);
          if (currentList != null && currentList.size() > 0){
            int direction = expressionParser.typecast(command, object, Number.class).intValue();
            ArrayList<Object> newList = new ArrayList<Object>(currentList);
            if(direction > 0){
              newList.remove(newList.size() - 1);
            } else {
              newList.remove(0);
            }
            subObject.put(subKey, newList);
          }
        }
      },
      new BasicUpdate("$pull", false) {
        @Override
        void mergeAction(String subKey, DBObject subObject, Object object) {
          if (object instanceof DBObject) {
            throw new FongoException(command + " with expressions is not support");
          }
          List currentList = expressionParser.typecast(command + " only works on arrays", subObject.get(subKey), List.class);
          if (currentList != null && currentList.size() > 0){
            
            ArrayList<Object> newList = new ArrayList<Object>();
            for (Object item : currentList) {
              if (!object.equals(item)){
                newList.add(item);
              }
            }
            subObject.put(subKey, newList);
          }
        }
      },
      new BasicUpdate("$pullAll", false) {
        @Override
        void mergeAction(String subKey, DBObject subObject, Object object) {
          List currentList = expressionParser.typecast(command + " only works on arrays", subObject.get(subKey), List.class);
          if (currentList != null && currentList.size() > 0){
            Set pullSet = new HashSet(expressionParser.typecast(command, object, List.class));
            ArrayList<Object> newList = new ArrayList<Object>();
            for (Object item : currentList) {
              if (!pullSet.contains(item)){
                newList.add(item);
              }
            }
            subObject.put(subKey, newList);
          }
        }
      },
      new BasicUpdate("$bit", false) {
        @Override
        void mergeAction(String subKey, DBObject subObject, Object object) {
          Number currentNumber = expressionParser.typecast(command + " only works on integers", subObject.get(subKey), Number.class);
          if (currentNumber != null){
            if (currentNumber instanceof Float || currentNumber instanceof Double){
              throw new FongoException(command + " only works on integers");
            }
            DBObject bitOps = expressionParser.typecast(command, object, DBObject.class);
            for (String op : bitOps.keySet()) {
              Number opValue = expressionParser.typecast(command + "." + op, bitOps.get(op), Number.class);
              if ("and".equals(op)){
                if (opValue instanceof Long || currentNumber instanceof Long){
                  currentNumber = currentNumber.longValue() & opValue.longValue();
                } else {
                  currentNumber = currentNumber.intValue() & opValue.intValue(); 
                }
              } else if ("or".equals(op)){
                if (opValue instanceof Long || currentNumber instanceof Long){
                  currentNumber = currentNumber.longValue() | opValue.longValue();
                } else {
                  currentNumber = currentNumber.intValue() | opValue.intValue(); 
                }
              } else {
                throw new FongoException(command + "." + op + " is not valid.");
              }
            }
            subObject.put(subKey, currentNumber);
          }
        }
      }
  );
  final Map<String, BasicUpdate> commandMap = createCommandMap();
  private Map<String, BasicUpdate> createCommandMap() {
    Map<String, BasicUpdate> map = new HashMap<String, BasicUpdate>();
    for (BasicUpdate item : commands){
      map.put(item.command, item);
    }
    return map;
  }
  
  public DBObject doUpdate(final DBObject obj, final DBObject update) {
    boolean updateDone = false;
    Set<String> seenKeys = new HashSet<String>();
    for (String command : update.keySet()) {
      BasicUpdate basicUpdate = commandMap.get(command);
      if (basicUpdate != null){
        debug("Doing update for command " + command);
        basicUpdate.doUpdate(obj, update, seenKeys);
        updateDone = true;
      } else if (command.startsWith("$")){
        throw new FongoException("usupported update: " + update);
      }
    }
    if (!updateDone){
      for (Iterator<String> iter = obj.keySet().iterator(); iter.hasNext();) {
        String key = iter.next();
        if (key != "_id"){
          iter.remove();
        }
      }
      obj.putAll(update);
    }
    return obj;
  }
}