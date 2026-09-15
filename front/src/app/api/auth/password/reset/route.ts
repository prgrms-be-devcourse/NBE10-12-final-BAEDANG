import { NextRequest } from 'next/server';
import { relayAuth } from '../../../../../lib/server/auth-relay';

export async function POST(request: NextRequest) {
  return relayAuth(request, 'password/reset');
}
