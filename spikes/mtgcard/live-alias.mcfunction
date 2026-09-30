# Run these commands in the DISPOSABLE local server console, without leading slash.
# Operator-created alias fixture; not a claim that ordinary survival players can clone blocks.
forceload add 0 0
setblock 0 80 0 mtgcard:oak_deckbox
data merge block 0 80 0 {DeckboxId:"11111111-1111-4111-8111-111111111111"}
item replace block 0 80 0 container.0 with mtgcard:card[minecraft:custom_data={mtg_uid:"22222222-2222-4222-8222-222222222222",mtg_foil:0b,mtg_meta:{id:"435589bb-27c6-4a6d-9d63-394d5092b9d8",scryfall_id:"435589bb-27c6-4a6d-9d63-394d5092b9d8",oracle_id:"4457ed35-7c10-48c8-9776-456485fdf070",name:"Lightning Bolt",set:"m10",collector_number:"146",layout:"normal"}}] 1
data get block 0 80 0
# Capture the backing .dat here as .cache/deckbox-before.dat (expected one slot).
clone 0 80 0 0 80 0 1 80 0 replace
data get block 1 80 0 DeckboxId
item replace block 1 80 0 container.0 with minecraft:air
data get block 0 80 0 Items
data get block 1 80 0 Items
# Capture the backing .dat here as .cache/deckbox-after.dat (expected zero slots).
# The original block still reports its card despite the shared backing record now being empty.
setblock 0 80 0 air destroy
data get entity @e[type=minecraft:item,x=0,y=80,z=0,distance=..5,limit=1,sort=nearest] Item
