#!/usr/bin/env python3
"""
Invert Whisper vocabulary from {token: id} to {id: token} format
"""
import json
import sys

def invert_vocab(input_file, output_file):
    """Invert vocabulary mapping"""
    with open(input_file, 'r') as f:
        vocab_token_to_id = json.load(f)
    
    # Invert: token -> id becomes id -> token
    vocab_id_to_token = {str(v): k for k, v in vocab_token_to_id.items()}
    
    print(f"Original vocab size: {len(vocab_token_to_id)} tokens")
    print(f"Inverted vocab size: {len(vocab_id_to_token)} IDs")
    print(f"Max token ID: {max(int(k) for k in vocab_id_to_token.keys())}")
    
    # Write inverted vocab
    with open(output_file, 'w') as f:
        json.dump(vocab_id_to_token, f, indent=2, ensure_ascii=False)
    
    print(f"Wrote inverted vocabulary to: {output_file}")

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: python3 invert_vocab.py <input_vocab.json> <output_vocab.json>")
        sys.exit(1)
    
    invert_vocab(sys.argv[1], sys.argv[2])
