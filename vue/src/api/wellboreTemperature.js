import request from '@/utils/request'

export const calculateWellboreTemperature = data => request.post('/wellbore/temperature/calculate', data, { timeout: 600000, headers: { 'Process-Env': 'prod' } })
