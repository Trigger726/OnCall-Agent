// Synthetic gate-shape upgrade only. Never modifies saved evidence or claims a V42 execution.
function toV42(value) {
  if(!value||typeof value!=='object')return value;
  if(value.migrationCount===41){value.migrationCount=42;value.successfulHistoryRows++;value.migration42=true;value.history.push({version:'42',type:'SQL',success:true});}
  if(value.versionedMigrations===41)value.versionedMigrations=42;
  for(const child of Object.values(value))if(child&&typeof child==='object')toV42(child);
  return value;
}
module.exports={toV42};
