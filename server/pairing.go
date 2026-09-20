package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"golang.org/x/crypto/pbkdf2"
	"golang.org/x/crypto/ssh"
)

type pairingRegistration struct {
	HostID    string `json:"hostId"`
	PairID    string `json:"pairId"`
	Sealed    string `json:"sealed"`
	PublicKey string `json:"publicKey"`
	Signature string `json:"signature"`
}
type laptopPairing struct {
	request pairingRegistration
	signer  ssh.Signer
}

func preparePairing(token string) (*laptopPairing, error) {
	phrase := os.Getenv("WFY_PAIRING_PHRASE")
	if phrase == "" {
		return nil, nil
	}
	normalized := strings.ToLower(strings.Join(strings.Fields(strings.ReplaceAll(phrase, "-", "")), ""))
	if !regexp.MustCompile(`^[a-f0-9]{32}$`).MatchString(normalized) {
		return nil, errors.New("Use the generated 32-character pairing code")
	}
	data, err := os.ReadFile(filepath.Join(os.Getenv("WFY_SSH_DIR"), "host_ed25519"))
	if err != nil {
		return nil, err
	}
	signer, err := ssh.ParsePrivateKey(data)
	if err != nil {
		return nil, err
	}
	public := signer.PublicKey().Marshal()
	digest := sha256.Sum256(public)
	hostID := hex.EncodeToString(digest[:])
	secret := pbkdf2.Key([]byte(normalized), []byte("Outpost pairing v1"), 120000, 32, sha256.New)
	lookup := sha256.Sum256(append(append([]byte{}, secret...), []byte("lookup")...))
	pairID := hex.EncodeToString(lookup[:])
	encryptionKey := sha256.Sum256(append(secret, []byte("profile")...))
	block, _ := aes.NewCipher(encryptionKey[:])
	gcm, _ := cipher.NewGCM(block)
	name := strings.TrimSpace(os.Getenv("WFY_HOST_NAME"))
	if name == "" {
		name, _ = os.Hostname()
	}
	if len(name) > 100 {
		name = string([]rune(name)[:min(len([]rune(name)), 60)])
	}
	_, backendPort, err := net.SplitHostPort(os.Getenv("WFY_LISTEN"))
	if err != nil {
		return nil, errors.New("Pairing requires an explicit WFY_LISTEN host:port")
	}
	user := os.Getenv("WFY_SSH_USER")
	if user == "" {
		user = "outpost"
	}
	profile, _ := json.Marshal(map[string]string{"hostId": hostID, "name": name, "user": user, "backendPort": backendPort, "token": token, "publicKey": base64.StdEncoding.EncodeToString(public)})
	nonce := make([]byte, gcm.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return nil, err
	}
	sealed := gcm.Seal(nonce, nonce, profile, []byte(hostID+"\n"+pairID))
	return &laptopPairing{request: pairingRegistration{HostID: hostID, PairID: pairID, Sealed: base64.StdEncoding.EncodeToString(sealed), PublicKey: base64.StdEncoding.EncodeToString(public)}, signer: signer}, nil
}
func (p *laptopPairing) register(client *ssh.Client) error {
	request := p.request
	proof := append(append([]byte("outpost-register-v1\x00"), client.SessionID()...), []byte(request.HostID+"\n"+request.PairID+"\n"+request.Sealed)...)
	signature, err := p.signer.Sign(rand.Reader, proof)
	if err != nil {
		return err
	}
	request.Signature = base64.StdEncoding.EncodeToString(ssh.Marshal(signature))
	payload, _ := json.Marshal(request)
	ok, _, err := client.SendRequest("outpost-register@v1", true, payload)
	if err != nil {
		return err
	}
	if !ok {
		return errors.New("Relay rejected registration: update the relay or disconnect another instance of this laptop")
	}
	return nil
}
