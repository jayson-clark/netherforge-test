local daily = nf.schedule.daily("18:00", function() end, { id = "it_daily", catch_up = true })
local weekly = nf.schedule.weekly("sat", "18:00", function() end)
local every_minute = nf.schedule.cron("* * * * *", function()
  log("minute")
end)
local now = nf.server.unix_time()
local soon = every_minute:next_run()
log(
  "scheduled",
  daily:next_run() > now,
  weekly:next_run() > now,
  soon > now and soon - now <= 60000
)
