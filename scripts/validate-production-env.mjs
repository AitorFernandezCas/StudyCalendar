import { loadEnv } from 'vite'
import { validateProductionEnv } from './production-env.mjs'

const errors = validateProductionEnv(loadEnv('production', process.cwd(), 'VITE_'))
if (errors.length) {
  console.error('No se puede publicar el frontend:\n' + errors.map(error => `- ${error}`).join('\n'))
  process.exitCode = 1
} else console.log('Variables del frontend de producción verificadas.')
