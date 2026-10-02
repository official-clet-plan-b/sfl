/** Provider boundary for local notification delivery and future approved gateways. */
export interface NotificationDispatchRequest {
  activationId: string;
  channels: string[];
  recipientCount: number;
}

export interface NotificationDispatchResult {
  provider: string;
  accepted: boolean;
  queuedAt: string;
}

export interface EmergencyNotificationProvider {
  dispatch(request: NotificationDispatchRequest): Promise<NotificationDispatchResult>;
}

export const mockEmergencyNotificationProvider: EmergencyNotificationProvider = {
  async dispatch() {
    return { provider: 'local-simulation', accepted: true, queuedAt: new Date().toISOString() };
  },
};

export const emergencyNotificationProvider = mockEmergencyNotificationProvider;
