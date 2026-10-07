import { run } from './main.ts'

const outcome = await run(process.argv.slice(2))
for (const line of outcome.out) process.stdout.write(`${line}\n`)
for (const line of outcome.err) process.stderr.write(`${line}\n`)
process.exitCode = outcome.code
